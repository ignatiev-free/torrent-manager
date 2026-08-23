package app.torrentmanager.report;

import app.torrentmanager.config.DailyReportProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
@ConditionalOnProperty(name = {"daily-report.enabled", "email-notifications.enabled"},
        havingValue = "true")
public class ReportController {
    private final DailyReportService reportService;
    private final DailyReportProperties properties;

    public ReportController(DailyReportService reportService, DailyReportProperties properties) {
        this.reportService = reportService;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ReportStatusResponse status() {
        return reportService.status();
    }

    @PostMapping("/send")
    public Map<String, String> send(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (!properties.manualEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        verifyToken(authorization);
        try {
            reportService.sendManual();
            return Map.of("status", "sent");
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    exception.getMessage(), exception);
        }
    }

    private void verifyToken(String authorization) {
        String expected = "Bearer " + properties.manualToken();
        if (authorization == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
