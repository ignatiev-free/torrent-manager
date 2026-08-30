package app.torrentmanager.report;

import app.torrentmanager.config.DailyReportProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReportControllerTests {
    @Test
    void requiresBearerTokenAndSendsWithCorrectToken() {
        DailyReportService service = mock(DailyReportService.class);
        ReportController controller = new ReportController(service, properties());

        assertThatThrownBy(() -> controller.send("Bearer wrong"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
        assertThat(controller.send("Bearer 1234567890abcdef"))
                .containsEntry("status", "sent");
        verify(service).sendManual();
    }

    private DailyReportProperties properties() {
        return new DailyReportProperties(true, LocalTime.of(9, 0), Duration.ofMinutes(1),
                Duration.ZERO, Path.of("state.json"), Path.of("actions.json"), 5000,
                20, 20, true, "1234567890abcdef", Duration.ofMinutes(5));
    }
}
