package app.torrentmanager.monitoring;

import java.time.Instant;
import java.util.UUID;

public record DownloadCompletionRecord(
        UUID id,
        String hash,
        String name,
        Instant completedAt,
        DeliveryStatus deliveryStatus,
        String channel,
        int attempts,
        Instant nextAttemptAt,
        Instant deliveredAt,
        String lastError
) {
    public static DownloadCompletionRecord from(DownloadCompletedEvent event, boolean notificationsEnabled) {
        return new DownloadCompletionRecord(
                UUID.randomUUID(),
                event.hash(),
                event.name(),
                event.detectedAt(),
                notificationsEnabled ? DeliveryStatus.PENDING : DeliveryStatus.NOT_CONFIGURED,
                notificationsEnabled ? "EMAIL" : null,
                0,
                notificationsEnabled ? event.detectedAt() : null,
                null,
                null);
    }

    public DownloadCompletionRecord delivered(Instant at) {
        return new DownloadCompletionRecord(id, hash, name, completedAt, DeliveryStatus.DELIVERED,
                channel, attempts + 1, null, at, null);
    }

    public DownloadCompletionRecord retry(Instant at, String error) {
        return new DownloadCompletionRecord(id, hash, name, completedAt, DeliveryStatus.PENDING,
                channel, attempts + 1, at, null, error);
    }

    public DownloadCompletionRecord failed(String error) {
        return new DownloadCompletionRecord(id, hash, name, completedAt, DeliveryStatus.FAILED,
                channel, attempts + 1, null, null, error);
    }
}
