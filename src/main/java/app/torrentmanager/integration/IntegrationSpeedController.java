package app.torrentmanager.integration;

import app.torrentmanager.notification.SpeedSnapshotService;
import java.util.Arrays;
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

    public IntegrationSpeedController(SpeedSnapshotService speed, Environment environment) {
        this.speed = speed;
        String token = environment.getProperty("TORRENT_INTEGRATION_TOKEN");
        authorization = token == null || token.isBlank() ? null : bytes("Bearer " + token);
        canRead = Arrays.stream(environment.getProperty("TORRENT_INTEGRATION_SCOPES", "").split("[,\\s]+"))
                .anyMatch("torrent:read"::equals);
    }

    @GetMapping("/api/integration/v1/speed")
    public ResponseEntity<?> speed(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String header) {
        if (authorization == null) return error(HttpStatus.NOT_FOUND, "INTEGRATION_DISABLED");
        if (header == null || !MessageDigest.isEqual(authorization, bytes(header))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store").body(new ApiError("UNAUTHORIZED"));
        }
        if (!canRead) return error(HttpStatus.FORBIDDEN, "INSUFFICIENT_SCOPE");
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(speed.read());
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ApiError> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SPEED_UNAVAILABLE");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(new ApiError(code));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    record ApiError(String code) { }
}
