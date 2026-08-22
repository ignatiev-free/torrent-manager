package app.torrentmanager.notification;

import app.torrentmanager.config.EmailNotificationProperties;
import app.torrentmanager.monitoring.DeliveryStatus;
import app.torrentmanager.monitoring.DownloadCompletionHistoryService;
import app.torrentmanager.monitoring.DownloadCompletionRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadNotificationServiceTests {
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void marksNotificationAsDelivered() {
        DownloadCompletionHistoryService history = mock(DownloadCompletionHistoryService.class);
        NotificationSender sender = mock(NotificationSender.class);
        DownloadCompletionRecord pending = pending(0, NOW);
        when(history.nextPending(NOW)).thenReturn(Optional.of(pending));

        service(history, sender).deliverPending();

        ArgumentCaptor<DownloadCompletionRecord> result =
                ArgumentCaptor.forClass(DownloadCompletionRecord.class);
        ArgumentCaptor<NotificationMessage> message = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().subject()).isEqualTo("Загрузка завершена — Film");
        verify(history).replace(org.mockito.ArgumentMatchers.eq(pending.id()), result.capture());
        assertThat(result.getValue().deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(result.getValue().attempts()).isEqualTo(1);
        assertThat(result.getValue().deliveredAt()).isEqualTo(NOW);
    }

    @Test
    void schedulesRetryAfterFirstFailure() {
        DownloadCompletionHistoryService history = mock(DownloadCompletionHistoryService.class);
        NotificationSender sender = mock(NotificationSender.class);
        DownloadCompletionRecord pending = pending(0, NOW);
        when(history.nextPending(NOW)).thenReturn(Optional.of(pending));
        doThrow(new IllegalStateException("SMTP unavailable")).when(sender)
                .send(org.mockito.ArgumentMatchers.any(NotificationMessage.class));

        service(history, sender).deliverPending();

        ArgumentCaptor<DownloadCompletionRecord> result =
                ArgumentCaptor.forClass(DownloadCompletionRecord.class);
        verify(history).replace(org.mockito.ArgumentMatchers.eq(pending.id()), result.capture());
        assertThat(result.getValue().deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(result.getValue().attempts()).isEqualTo(1);
        assertThat(result.getValue().nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(result.getValue().lastError()).isEqualTo("SMTP unavailable");
    }

    @Test
    void marksNotificationAsFailedAfterFifthAttempt() {
        DownloadCompletionHistoryService history = mock(DownloadCompletionHistoryService.class);
        NotificationSender sender = mock(NotificationSender.class);
        DownloadCompletionRecord pending = pending(4, NOW);
        when(history.nextPending(NOW)).thenReturn(Optional.of(pending));
        doThrow(new IllegalStateException("SMTP unavailable")).when(sender)
                .send(org.mockito.ArgumentMatchers.any(NotificationMessage.class));

        service(history, sender).deliverPending();

        ArgumentCaptor<DownloadCompletionRecord> result =
                ArgumentCaptor.forClass(DownloadCompletionRecord.class);
        verify(history).replace(org.mockito.ArgumentMatchers.eq(pending.id()), result.capture());
        assertThat(result.getValue().deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(result.getValue().attempts()).isEqualTo(5);
        assertThat(result.getValue().nextAttemptAt()).isNull();
    }

    private DownloadNotificationService service(DownloadCompletionHistoryService history,
                                                NotificationSender sender) {
        EmailNotificationProperties properties = new EmailNotificationProperties(
                true, "recipient@example.com", Duration.ofSeconds(30));
        return new DownloadNotificationService(history, sender, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DownloadCompletionRecord pending(int attempts, Instant nextAttemptAt) {
        return new DownloadCompletionRecord(UUID.randomUUID(), "hash", "Film", NOW,
                DeliveryStatus.PENDING, "EMAIL", attempts, nextAttemptAt, null, null);
    }
}
