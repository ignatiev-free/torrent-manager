package app.torrentmanager.monitoring;

import java.time.Instant;

public record DownloadCompletedEvent(String hash, String name, Instant detectedAt) {
}
