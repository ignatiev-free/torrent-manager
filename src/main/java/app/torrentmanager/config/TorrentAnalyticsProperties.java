package app.torrentmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("torrent-analytics")
public record TorrentAnalyticsProperties(
        boolean enabled,
        Duration pollInterval,
        Duration initialDelay,
        Path stateFile,
        Duration noProgressAfter,
        Duration noSeedsAfter,
        double nearCompleteProgress,
        Duration nearCompleteStalledAfter,
        int maxUnproductiveAttempts,
        Duration forcedStalledAfter,
        Duration oldTorrentAfter,
        double oldTorrentProgress,
        DataSize stalledSpeedThreshold
) {
    public TorrentAnalyticsProperties {
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("torrent-analytics.poll-interval должен быть больше нуля");
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("torrent-analytics.initial-delay не может быть отрицательным");
        }
        if (stateFile == null) {
            throw new IllegalArgumentException("torrent-analytics.state-file должен быть задан");
        }
        if (nearCompleteProgress <= 0 || nearCompleteProgress > 1
                || oldTorrentProgress < 0 || oldTorrentProgress > 1) {
            throw new IllegalArgumentException("Порог прогресса должен находиться в диапазоне 0..1");
        }
        if (maxUnproductiveAttempts < 1) {
            throw new IllegalArgumentException("max-unproductive-attempts должен быть больше нуля");
        }
    }
}
