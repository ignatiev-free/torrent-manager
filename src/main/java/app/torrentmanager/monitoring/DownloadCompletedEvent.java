package app.torrentmanager.monitoring;

import java.time.Instant;

public record DownloadCompletedEvent(String hash, String name, Instant detectedAt,
                                     Long totalDurationSeconds, Long activeDurationSeconds) {
    public DownloadCompletedEvent(String hash, String name, Instant detectedAt) {
        this(hash, name, detectedAt, null, null);
    }
}
