package app.torrentmanager.notification;

import app.torrentmanager.config.DownloadStallAlertProperties;
import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.Torrent;
import app.torrentmanager.qbit.TransferInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@ConditionalOnProperty(
        name = {"email-notifications.enabled", "download-stall-alert.enabled"},
        havingValue = "true")
public class DownloadStallAlertService {
    private static final Logger log = LoggerFactory.getLogger(DownloadStallAlertService.class);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z");

    private final QBitClient client;
    private final NotificationSender sender;
    private final DownloadStallAlertProperties properties;
    private final Clock clock;

    private Instant lowSpeedSince;
    private Instant recoverySince;
    private boolean alertSent;

    @Autowired
    public DownloadStallAlertService(QBitClient client,
                                     NotificationSender sender,
                                     DownloadStallAlertProperties properties) {
        this(client, sender, properties, Clock.systemDefaultZone());
    }

    DownloadStallAlertService(QBitClient client,
                              NotificationSender sender,
                              DownloadStallAlertProperties properties,
                              Clock clock) {
        this.client = client;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${download-stall-alert.poll-interval:1m}",
            initialDelayString = "${download-stall-alert.initial-delay:2m}")
    public void inspect() {
        try {
            inspectAt(client.getTransferInfo(), client.getTorrents(), clock.instant());
        } catch (RuntimeException exception) {
            log.error("Не удалось проверить общую остановку загрузок: {}",
                    exception.getMessage(), exception);
        }
    }

    void inspectAt(TransferInfo transferInfo, List<Torrent> torrents, Instant now) {
        List<Torrent> pending = torrents.stream()
                .filter(torrent -> !torrent.isComplete())
                .filter(torrent -> torrent.isActivelyDownloading() || torrent.isQueuedForDownload())
                .toList();

        if (pending.isEmpty()) {
            closeIncidentWithoutPendingDownloads(now);
            return;
        }

        long speed = transferInfo.downloadSpeed();
        if (speed <= properties.zeroSpeedThreshold().toBytes()) {
            recoverySince = null;
            if (lowSpeedSince == null) {
                lowSpeedSince = now;
                log.warn("Обнаружена общая низкая скорость загрузок: {} KiB/s; наблюдение начато",
                        speed / 1024);
            }
            if (!alertSent && !now.isBefore(lowSpeedSince.plus(properties.alertAfter()))) {
                sendStallAlert(now, speed, pending);
            }
            return;
        }

        lowSpeedSince = null;
        if (!alertSent) {
            return;
        }

        if (speed < properties.recoverySpeed().toBytes()) {
            recoverySince = null;
            return;
        }

        if (recoverySince == null) {
            recoverySince = now;
        }
        if (!now.isBefore(recoverySince.plus(properties.recoveryWindow()))) {
            sendRecovery(now, speed, pending.size());
        }
    }

    private void sendStallAlert(Instant now, long speed, List<Torrent> pending) {
        long active = pending.stream().filter(Torrent::isActivelyDownloading).count();
        long queued = pending.stream().filter(Torrent::isQueuedForDownload).count();
        Duration observed = Duration.between(lowSpeedSince, now);
        NotificationMessage message = new NotificationMessage(
                "Torrent Manager: загрузки остановились",
                """
                        Torrent Manager обнаружил длительное отсутствие общей скорости загрузки.

                        Скорость: %d KiB/s
                        Наблюдается: %d мин.
                        Активных незавершённых: %d
                        В очереди: %d
                        Время: %s

                        Менеджер продолжает применять штатную ротацию торрентов. Проверьте qBittorrent удалённо;
                        если скорость не восстановилась, может потребоваться перезапуск сервера.
                        """.formatted(
                        speed / 1024,
                        observed.toMinutes(),
                        active,
                        queued,
                        formatTime(now)));
        try {
            sender.send(message);
            alertSent = true;
            log.error("Отправлено предупреждение об общей остановке загрузок после {} минут",
                    observed.toMinutes());
        } catch (RuntimeException exception) {
            log.error("Не удалось отправить предупреждение об остановке загрузок: {}",
                    exception.getMessage(), exception);
        }
    }

    private void sendRecovery(Instant now, long speed, int pendingCount) {
        try {
            sender.send(new NotificationMessage(
                    "Torrent Manager: скорость загрузки восстановилась",
                    """
                            Общая скорость загрузки снова стабильна.

                            Скорость: %d KiB/s
                            Незавершённых активных или ожидающих: %d
                            Время: %s
                            """.formatted(speed / 1024, pendingCount, formatTime(now))));
            log.info("Отправлено уведомление о восстановлении скорости: {} KiB/s", speed / 1024);
            resetIncident();
        } catch (RuntimeException exception) {
            log.error("Не удалось отправить уведомление о восстановлении скорости: {}",
                    exception.getMessage(), exception);
        }
    }

    private void closeIncidentWithoutPendingDownloads(Instant now) {
        if (alertSent) {
            try {
                sender.send(new NotificationMessage(
                        "Torrent Manager: активных загрузок больше нет",
                        "После предупреждения об остановке скорости не осталось активных или ожидающих загрузок.\n\n"
                                + "Время: " + formatTime(now)));
            } catch (RuntimeException exception) {
                log.error("Не удалось отправить уведомление о завершении инцидента: {}",
                        exception.getMessage(), exception);
                return;
            }
        }
        resetIncident();
    }

    private void resetIncident() {
        lowSpeedSince = null;
        recoverySince = null;
        alertSent = false;
    }

    private String formatTime(Instant instant) {
        ZoneId zone = clock.getZone();
        return DATE_TIME.withZone(zone).format(instant);
    }
}
