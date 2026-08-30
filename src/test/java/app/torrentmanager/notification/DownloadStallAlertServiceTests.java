package app.torrentmanager.notification;

import app.torrentmanager.config.DownloadStallAlertProperties;
import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.Torrent;
import app.torrentmanager.qbit.TransferInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DownloadStallAlertServiceTests {
    private static final Instant START = Instant.parse("2026-08-21T10:00:00Z");

    @Test
    void alertsOnceOnlyAfterConfiguredObservationWindow() {
        NotificationSender sender = mock(NotificationSender.class);
        DownloadStallAlertService service = service(sender);

        service.inspectAt(transfer(0), List.of(active("one")), START);
        service.inspectAt(transfer(0), List.of(active("two")), START.plus(Duration.ofMinutes(19)));
        verify(sender, never()).send(org.mockito.ArgumentMatchers.any());

        service.inspectAt(transfer(0), List.of(active("three")), START.plus(Duration.ofMinutes(20)));
        service.inspectAt(transfer(0), List.of(active("four")), START.plus(Duration.ofMinutes(30)));

        ArgumentCaptor<NotificationMessage> message = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(sender, times(1)).send(message.capture());
        assertThat(message.getValue().subject()).contains("загрузки остановились");
        assertThat(message.getValue().body()).contains("Наблюдается: 20 мин.");
        assertThat(message.getValue().htmlBody()).contains("ТРЕБУЕТСЯ ВНИМАНИЕ",
                "Общая скорость загрузки пропала", "Наблюдается");
    }

    @Test
    void sendsRecoveryOnlyAfterStableRecoveryWindow() {
        NotificationSender sender = mock(NotificationSender.class);
        DownloadStallAlertService service = service(sender);
        List<Torrent> torrents = List.of(active("one"));

        service.inspectAt(transfer(0), torrents, START);
        service.inspectAt(transfer(0), torrents, START.plus(Duration.ofMinutes(20)));
        service.inspectAt(transfer(200 * 1024), torrents, START.plus(Duration.ofMinutes(21)));
        service.inspectAt(transfer(200 * 1024), torrents, START.plus(Duration.ofMinutes(22)));
        verify(sender, times(1)).send(org.mockito.ArgumentMatchers.any());

        service.inspectAt(transfer(200 * 1024), torrents, START.plus(Duration.ofMinutes(23)));

        ArgumentCaptor<NotificationMessage> messages = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(sender, times(2)).send(messages.capture());
        assertThat(messages.getAllValues()).extracting(NotificationMessage::subject)
                .containsExactly(
                        "Torrent Manager: загрузки остановились",
                        "Torrent Manager: скорость загрузки восстановилась");
    }

    @Test
    void briefRecoveryRestartsObservationWindow() {
        NotificationSender sender = mock(NotificationSender.class);
        DownloadStallAlertService service = service(sender);
        List<Torrent> torrents = List.of(active("one"));

        service.inspectAt(transfer(0), torrents, START);
        service.inspectAt(transfer(50 * 1024), torrents, START.plus(Duration.ofMinutes(10)));
        service.inspectAt(transfer(0), torrents, START.plus(Duration.ofMinutes(11)));
        service.inspectAt(transfer(0), torrents, START.plus(Duration.ofMinutes(30)));
        verify(sender, never()).send(org.mockito.ArgumentMatchers.any());

        service.inspectAt(transfer(0), torrents, START.plus(Duration.ofMinutes(31)));
        verify(sender).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotAlertWhenThereAreNoActiveOrQueuedDownloads() {
        NotificationSender sender = mock(NotificationSender.class);
        DownloadStallAlertService service = service(sender);
        Torrent stopped = new Torrent("stopped", "stopped", "stoppedDL", 0.5, 0, 0, 0, 1);

        service.inspectAt(transfer(0), List.of(stopped), START);
        service.inspectAt(transfer(0), List.of(stopped), START.plus(Duration.ofHours(1)));

        verify(sender, never()).send(org.mockito.ArgumentMatchers.any());
    }

    private DownloadStallAlertService service(NotificationSender sender) {
        DownloadStallAlertProperties properties = new DownloadStallAlertProperties(
                true,
                Duration.ofMinutes(1),
                Duration.ofMinutes(2),
                DataSize.ofKilobytes(10),
                Duration.ofMinutes(20),
                DataSize.ofKilobytes(100),
                Duration.ofMinutes(2));
        return new DownloadStallAlertService(mock(QBitClient.class), sender, properties,
                Clock.fixed(START, ZoneOffset.UTC));
    }

    private TransferInfo transfer(long speed) {
        return new TransferInfo(speed, 0, false);
    }

    private Torrent active(String hash) {
        return new Torrent(hash, hash, "downloading", 0.5, 0, 1, 1, 1);
    }
}
