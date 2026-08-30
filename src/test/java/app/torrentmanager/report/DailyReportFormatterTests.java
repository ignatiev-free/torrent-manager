package app.torrentmanager.report;

import app.torrentmanager.analytics.ManagerActionEvent;
import app.torrentmanager.analytics.ManagerActionType;
import app.torrentmanager.analytics.TorrentAnalyticsRecord;
import app.torrentmanager.config.DailyReportProperties;
import app.torrentmanager.config.TorrentAnalyticsProperties;
import app.torrentmanager.monitoring.DeliveryStatus;
import app.torrentmanager.monitoring.DownloadCompletionRecord;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DailyReportFormatterTests {
    private static final Instant NOW = Instant.parse("2026-08-23T09:00:00Z");

    @Test
    void includesCompletionsProgressDeltaActionsAndAttention() {
        DailyReportFormatter formatter = new DailyReportFormatter(reportProperties(),
                new TorrentAttentionAnalyzer(analyticsProperties()));
        TorrentAnalyticsRecord torrent = new TorrentAnalyticsRecord("hash", "Slow movie",
                NOW.minus(Duration.ofDays(20)), NOW.minus(Duration.ofDays(10)), NOW,
                "stalledDL", 0.99, NOW.minus(Duration.ofDays(2)), 3600, 120,
                NOW.minus(Duration.ofDays(4)), 0, 0, 0, 2, 3, 1, 5);
        DownloadCompletionRecord completed = new DownloadCompletionRecord(UUID.randomUUID(),
                "done", "Completed movie", NOW.minusSeconds(60), DeliveryStatus.DELIVERED,
                "EMAIL", 1, null, NOW.minusSeconds(50), null);

        String report = formatter.format(NOW.minus(Duration.ofDays(1)), NOW,
                List.of(completed), List.of(torrent), List.of(
                        new ManagerActionEvent("hash", "Slow movie", ManagerActionType.STOPPED,
                                NOW.minusSeconds(10), 0.99),
                        new ManagerActionEvent("hash", "Slow movie", ManagerActionType.FAIR_ROTATION,
                                NOW.minusSeconds(5), 0.99)), Map.of("hash", 0.95));

        assertThat(report).contains("Completed movie", "Slow movie — 99,0%",
                "за период +4%", "активно 1 ч.", "в очереди 2 мин.",
                "Остановлено: 1", "Возобновлено всего: 1",
                "Из них через справедливую ротацию: 1",
                "прогресс почти завершён и не меняется");

        String html = formatter.formatHtml(NOW.minus(Duration.ofDays(1)), NOW,
                List.of(completed), List.of(torrent), List.of(), Map.of("hash", 0.95));
        assertThat(html).contains("<!doctype html>", "Требуют внимания", "Slow movie",
                "за период +4%", "overflow-wrap:anywhere");
    }

    private DailyReportProperties reportProperties() {
        return new DailyReportProperties(true, LocalTime.of(9, 0), Duration.ofMinutes(1),
                Duration.ZERO, Path.of("state.json"), Path.of("actions.json"), 5000,
                20, 20, true, "1234567890abcdef", Duration.ofMinutes(5));
    }

    private TorrentAnalyticsProperties analyticsProperties() {
        return new TorrentAnalyticsProperties(true, Duration.ofMinutes(1), Duration.ZERO,
                Path.of("analytics.json"), Duration.ofDays(3), Duration.ofDays(3), 0.95,
                Duration.ofDays(1), 5, Duration.ofHours(6), Duration.ofDays(14), 0.10,
                DataSize.ofKilobytes(10));
    }
}
