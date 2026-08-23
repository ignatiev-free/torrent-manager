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
        String html = incidentHtml("ТРЕБУЕТСЯ ВНИМАНИЕ", "Общая скорость загрузки пропала",
                "#dc2626", List.of(
                        new Metric("Скорость", speed / 1024 + " KiB/s"),
                        new Metric("Наблюдается", observed.toMinutes() + " мин."),
                        new Metric("Активных", Long.toString(active)),
                        new Metric("В очереди", Long.toString(queued))),
                "Менеджер продолжает штатную ротацию. Если скорость не восстановится, проверьте qBittorrent, сеть и состояние сервера.", now);
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
                        formatTime(now)), html);
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
            String html = incidentHtml("РАБОТА ВОССТАНОВЛЕНА", "Скорость загрузки снова стабильна",
                    "#16a34a", List.of(
                            new Metric("Скорость", speed / 1024 + " KiB/s"),
                            new Metric("Активных и ожидающих", Integer.toString(pendingCount))),
                    "Инцидент закрыт автоматически после подтверждённого периода стабильной скорости.", now);
            sender.send(new NotificationMessage(
                    "Torrent Manager: скорость загрузки восстановилась",
                    """
                            Общая скорость загрузки снова стабильна.

                            Скорость: %d KiB/s
                            Незавершённых активных или ожидающих: %d
                            Время: %s
                            """.formatted(speed / 1024, pendingCount, formatTime(now)), html));
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
                String html = incidentHtml("ИНЦИДЕНТ ЗАКРЫТ", "Активных загрузок больше нет",
                        "#2563eb", List.of(new Metric("Активных и ожидающих", "0")),
                        "После предупреждения в qBittorrent не осталось активных или ожидающих загрузок.", now);
                sender.send(new NotificationMessage(
                        "Torrent Manager: активных загрузок больше нет",
                        "После предупреждения об остановке скорости не осталось активных или ожидающих загрузок.\n\n"
                                + "Время: " + formatTime(now), html));
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

    private String incidentHtml(String label, String title, String color, List<Metric> metrics,
                                String description, Instant now) {
        StringBuilder cards = new StringBuilder();
        for (Metric metric : metrics) {
            cards.append("<div style=\"flex:1;min-width:130px;background:#fff;border-radius:10px;padding:14px;border-top:3px solid ")
                    .append(color).append("\"><div style=\"color:#6b7280;font-size:12px\">")
                    .append(metric.label()).append("</div><div style=\"font-size:20px;font-weight:700;margin-top:4px\">")
                    .append(metric.value()).append("</div></div>");
        }
        return """
                <!doctype html><html lang="ru"><body style="margin:0;background:#f3f4f6;color:#111827;font-family:Arial,sans-serif">
                <div style="max-width:620px;margin:0 auto;padding:20px 12px">
                  <div style="background:#172033;color:#fff;border-radius:14px;padding:22px;border-bottom:4px solid %s">
                    <div style="font-size:13px;color:#cbd5e1;font-weight:700">%s</div>
                    <div style="font-size:21px;font-weight:700;margin-top:8px">%s</div>
                    <div style="color:#cbd5e1;font-size:13px;margin-top:8px">%s</div>
                  </div>
                  <div style="display:flex;gap:8px;margin:12px 0;flex-wrap:wrap">%s</div>
                  <div style="background:#fff;border-radius:12px;padding:16px;line-height:1.6;color:#4b5563">%s</div>
                </div></body></html>
                """.formatted(color, label, title, formatTime(now), cards, description);
    }

    private record Metric(String label, String value) { }
}
