package app.torrentmanager.report;

import app.torrentmanager.analytics.TorrentAnalyticsRecord;
import app.torrentmanager.config.TorrentAnalyticsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TorrentAttentionAnalyzerTests {
    private static final Instant NOW = Instant.parse("2026-08-23T09:00:00Z");

    @Test
    void prioritizesNearCompleteStalledTorrent() {
        TorrentAnalyticsRecord record = record(0.999, NOW.minus(Duration.ofDays(2)), null,
                "stalledDL", 0, 0);

        List<AttentionItem> result = new TorrentAttentionAnalyzer(properties())
                .analyze(List.of(record), NOW);

        assertThat(result).singleElement()
                .extracting(AttentionItem::reason)
                .isEqualTo("прогресс почти завершён и не меняется");
    }

    @Test
    void reportsLongAbsenceOfSeeds() {
        TorrentAnalyticsRecord record = record(0.03, NOW.minus(Duration.ofHours(1)),
                NOW.minus(Duration.ofDays(4)), "stalledDL", 0, 0);

        assertThat(new TorrentAttentionAnalyzer(properties()).analyze(List.of(record), NOW))
                .singleElement().extracting(AttentionItem::reason)
                .isEqualTo("нет доступных сидов");
    }

    private TorrentAnalyticsRecord record(double progress, Instant lastProgress, Instant noSeeds,
                                          String state, long speed, int attempts) {
        return new TorrentAnalyticsRecord("hash", "Movie", NOW.minus(Duration.ofDays(20)),
                NOW.minus(Duration.ofDays(10)), NOW, state, progress, lastProgress,
                100, 20, noSeeds, speed, 0, 0, 2, 2, 1, attempts);
    }

    private TorrentAnalyticsProperties properties() {
        return new TorrentAnalyticsProperties(true, Duration.ofMinutes(1), Duration.ZERO,
                Path.of("unused.json"), Duration.ofDays(3), Duration.ofDays(3), 0.95,
                Duration.ofDays(1), 5, Duration.ofHours(6), Duration.ofDays(14), 0.10,
                DataSize.ofKilobytes(10));
    }
}
