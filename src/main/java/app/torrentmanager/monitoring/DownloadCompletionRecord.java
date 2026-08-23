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
        Integer attempts,
        Instant nextAttemptAt,
        Instant deliveredAt,
        String lastError,
        Long totalDurationSeconds,
        Long activeDurationSeconds
) {
    public DownloadCompletionRecord(UUID id, String hash, String name, Instant completedAt,
                                    DeliveryStatus deliveryStatus, String channel, Integer attempts,
                                    Instant nextAttemptAt, Instant deliveredAt, String lastError) {
        this(id, hash, name, completedAt, deliveryStatus, channel, attempts, nextAttemptAt,
                deliveredAt, lastError, null, null);
    }

    public DownloadCompletionRecord {
        if (deliveryStatus == null) {
            deliveryStatus = DeliveryStatus.NOT_CONFIGURED;
        }
        if (attempts == null) {
            attempts = 0;
        }
    }

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
                null,
                event.totalDurationSeconds(),
                event.activeDurationSeconds());
    }

    public DownloadCompletionRecord delivered(Instant at) {
        return new DownloadCompletionRecord(id, hash, name, completedAt, DeliveryStatus.DELIVERED,
                channel, attempts + 1, null, at, null, totalDurationSeconds, activeDurationSeconds);
    }

    public DownloadCompletionRecord retry(Instant at, String error) {
        return new DownloadCompletionRecord(id, hash, name, completedAt, DeliveryStatus.PENDING,
                channel, attempts + 1, at, null, error, totalDurationSeconds, activeDurationSeconds);
    }

    public DownloadCompletionRecord failed(String error) {
        return new DownloadCompletionRecord(id, hash, name, completedAt, DeliveryStatus.FAILED,
                channel, attempts + 1, null, null, error, totalDurationSeconds, activeDurationSeconds);
    }
}
