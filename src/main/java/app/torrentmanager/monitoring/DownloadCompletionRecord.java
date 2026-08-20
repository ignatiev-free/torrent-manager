package app.torrentmanager.monitoring;

import java.time.Instant;
import java.util.UUID;

public record DownloadCompletionRecord(
        UUID id,
        String hash,
        String name,
        Instant completedAt,
        DeliveryStatus deliveryStatus
) {
    public static DownloadCompletionRecord from(DownloadCompletedEvent event) {
        return new DownloadCompletionRecord(
                UUID.randomUUID(),
                event.hash(),
                event.name(),
                event.detectedAt(),
                DeliveryStatus.NOT_CONFIGURED);
    }
}
