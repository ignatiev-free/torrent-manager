package app.torrentmanager.integration;

import app.torrentmanager.notification.SpeedSnapshotService;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Supplier;
import app.torrentmanager.report.DailyReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IntegrationSpeedController {
    private final SpeedSnapshotService speed;
    private final byte[] authorization;
    private final boolean canRead;
    private final byte[] writeAuthorization;
    private final TorrentIntegrationQueries queries;
    private final IntegrationOperations operations;

    IntegrationSpeedController(SpeedSnapshotService speed, Environment environment) {
        this(speed, environment, null, null);
    }

    @Autowired
    public IntegrationSpeedController(SpeedSnapshotService speed, Environment environment,
                                      TorrentIntegrationQueries queries, IntegrationOperations operations) {
        this.queries = queries;
        this.operations = operations;
        String writeToken = environment.getProperty("TORRENT_INTEGRATION_WRITE_TOKEN");
        writeAuthorization = writeToken == null || writeToken.isBlank() ? null : bytes("Bearer " + writeToken);
        this.speed = speed;
        String token = environment.getProperty("TORRENT_INTEGRATION_TOKEN");
        authorization = token == null || token.isBlank() ? null : bytes("Bearer " + token);
        canRead = Arrays.stream(environment.getProperty("TORRENT_INTEGRATION_SCOPES", "").split("[,\\s]+"))
                .anyMatch("torrent:read"::equals);
    }

    @GetMapping("/api/integration/v1/speed")
    public ResponseEntity<?> speed(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String header) {
        return read(header, speed::read);
    }

    @GetMapping("/api/integration/v1/overview")
    ResponseEntity<?> overview(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token) {
        return read(token, queries::overview);
    }

    @GetMapping("/api/integration/v1/downloads")
    ResponseEntity<?> downloads(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
                                @RequestParam(defaultValue = "0") int page) {
        return read(token, () -> queries.downloads(page));
    }

    @GetMapping("/api/integration/v1/completions")
    ResponseEntity<?> completions(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
                                  @RequestParam(defaultValue = "0") int page) {
        return read(token, () -> queries.completions(page));
    }

    @GetMapping("/api/integration/v1/storage")
    ResponseEntity<?> storage(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token) {
        return read(token, queries::storage);
    }

    @GetMapping("/api/integration/v1/reports/status")
    ResponseEntity<?> reportStatus(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token) {
        return read(token, queries::reports);
    }

    @PutMapping("/api/integration/v1/speed")
    ResponseEntity<?> changeSpeed(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
                                  @RequestHeader(value = "Idempotency-Key", required = false) UUID key,
                                  @RequestBody SpeedSnapshotService.Change change) {
        return write(token, key, HttpStatus.OK, () -> speed.change(change));
    }

    @PostMapping("/api/integration/v1/reports/send")
    ResponseEntity<?> sendReport(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
                                 @RequestHeader(value = "Idempotency-Key", required = false) UUID key) {
        return write(token, key, HttpStatus.ACCEPTED, () -> new ReportAccepted(queries.sendReport()));
    }

    private ResponseEntity<?> read(String token, Supplier<?> action) {
        var denied = authorize(token, false);
        return denied == null ? response(HttpStatus.OK, action.get()) : denied;
    }

    private ResponseEntity<?> write(String token, UUID key, HttpStatus status, Supplier<?> action) {
        var denied = authorize(token, true);
        if (denied != null) return denied;
        if (key == null) return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED");
        if (!operations.reserve(key)) return error(HttpStatus.CONFLICT, "OPERATION_ALREADY_SEEN");
        return response(status, action.get());
    }

    private ResponseEntity<?> authorize(String header, boolean write) {
        if (authorization == null && writeAuthorization == null) return error(HttpStatus.NOT_FOUND, "INTEGRATION_DISABLED");
        boolean reader = matches(authorization, header);
        boolean writer = matches(writeAuthorization, header);
        if (!reader && !writer) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store").body(new ApiError("UNAUTHORIZED"));
        }
        return (write ? writer && !reader : reader && canRead)
                ? null : error(HttpStatus.FORBIDDEN, "INSUFFICIENT_SCOPE");
    }

    private static boolean matches(byte[] expected, String header) {
        return expected != null && header != null && MessageDigest.isEqual(expected, bytes(header));
    }

    private static ResponseEntity<?> response(HttpStatus status, Object body) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> invalid() { return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"); }

    @ExceptionHandler(SpeedSnapshotService.StaleSpeed.class)
    ResponseEntity<ApiError> stale() { return error(HttpStatus.CONFLICT, "STALE_SPEED"); }

    @ExceptionHandler(DailyReportService.ReportCooldownException.class)
    ResponseEntity<ApiError> cooldown(DailyReportService.ReportCooldownException failure) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(failure.retryAfterSeconds()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(new ApiError("REPORT_COOLDOWN"));
    }

    record ReportAccepted(boolean queued) { }
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ApiError> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "INTEGRATION_UNAVAILABLE");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(new ApiError(code));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    record ApiError(String code) { }
}
