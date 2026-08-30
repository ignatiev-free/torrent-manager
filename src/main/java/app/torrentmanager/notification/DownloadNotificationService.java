package app.torrentmanager.notification;

import app.torrentmanager.config.EmailNotificationProperties;
import app.torrentmanager.monitoring.DownloadCompletionHistoryService;
import app.torrentmanager.monitoring.DownloadCompletionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.web.util.HtmlUtils;

@Service
@ConditionalOnProperty(name = "email-notifications.enabled", havingValue = "true")
public class DownloadNotificationService {
    private static final Logger log = LoggerFactory.getLogger(DownloadNotificationService.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(30), Duration.ofHours(2));
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z");

    private final DownloadCompletionHistoryService history;
    private final NotificationSender sender;
    private final EmailNotificationProperties properties;
    private final Clock clock;

    @Autowired
    public DownloadNotificationService(DownloadCompletionHistoryService history,
                                       NotificationSender sender,
                                       EmailNotificationProperties properties) {
        this(history, sender, properties, Clock.systemDefaultZone());
    }

    DownloadNotificationService(DownloadCompletionHistoryService history,
                                NotificationSender sender,
                                EmailNotificationProperties properties,
                                Clock clock) {
        this.history = history;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${email-notifications.worker-interval:30s}")
    public void deliverPending() {
        if (!properties.enabled()) {
            return;
        }

        Instant now = clock.instant();
        history.nextPending(now).ifPresent(record -> deliver(record, now));
    }

    private void deliver(DownloadCompletionRecord record, Instant now) {
        try {
            sender.send(messageFor(record));
            history.replace(record.id(), record.delivered(now));
            log.info("Уведомление о завершении загрузки отправлено: '{}' (канал={})",
                    record.name(), sender.channel());
        } catch (RuntimeException exception) {
            int attempt = record.attempts() + 1;
            String error = safeError(exception);
            if (attempt >= MAX_ATTEMPTS) {
                history.replace(record.id(), record.failed(error));
                log.error("Не удалось отправить уведомление после {} попыток: '{}' (канал={}): {}",
                        attempt, record.name(), sender.channel(), error);
                return;
            }

            Duration delay = RETRY_DELAYS.get(attempt - 1);
            history.replace(record.id(), record.retry(now.plus(delay), error));
            log.warn("Не удалось отправить уведомление: '{}'; повтор через {} (попытка {}/{}): {}",
                    record.name(), delay, attempt, MAX_ATTEMPTS, error);
        }
    }

    private NotificationMessage messageFor(DownloadCompletionRecord record) {
        String total = duration(record.totalDurationSeconds());
        String active = duration(record.activeDurationSeconds());
        String body = """
                Torrent Manager обнаружил завершение загрузки.

                Название: %s
                Hash: %s
                Завершено: %s
                Общее время с очередью: %s
                Активная загрузка, наблюдаемая менеджером: %s
                """.formatted(
                record.name(),
                record.hash(),
                DATE_TIME.format(record.completedAt().atZone(ZoneId.systemDefault())),
                total,
                active);
        String html = """
                <!doctype html><html lang="ru"><body style="margin:0;background:#f3f4f6;color:#111827;font-family:Arial,sans-serif">
                <div style="max-width:620px;margin:0 auto;padding:20px 12px">
                  <div style="background:#172033;color:#fff;border-radius:14px;padding:22px;border-bottom:4px solid #16a34a">
                    <div style="font-size:13px;color:#86efac;font-weight:700">ЗАГРУЗКА ЗАВЕРШЕНА</div>
                    <div style="font-size:21px;font-weight:700;margin-top:8px;overflow-wrap:anywhere">%s</div>
                  </div>
                  <div style="display:flex;gap:8px;margin:12px 0;flex-wrap:wrap">
                    %s%s
                  </div>
                  <div style="background:#fff;border-radius:12px;padding:16px;line-height:1.7">
                    <div style="color:#6b7280;font-size:12px">Завершено</div><b>%s</b>
                    <div style="color:#6b7280;font-size:12px;margin-top:10px">Hash</div>
                    <div style="font-family:monospace;overflow-wrap:anywhere">%s</div>
                  </div>
                </div></body></html>
                """.formatted(
                HtmlUtils.htmlEscape(record.name()),
                metric("Общее время с очередью", total, "#2563eb"),
                metric("Активная загрузка (наблюдаемая)", active, "#16a34a"),
                DATE_TIME.format(record.completedAt().atZone(ZoneId.systemDefault())),
                HtmlUtils.htmlEscape(record.hash()));
        return new NotificationMessage("Загрузка завершена — " + record.name(), body, html);
    }

    private String metric(String label, String value, String color) {
        return "<div style=\"flex:1;min-width:220px;background:#fff;border-radius:10px;padding:14px;border-top:3px solid "
                + color + "\"><div style=\"color:#6b7280;font-size:12px\">" + label
                + "</div><div style=\"font-size:20px;font-weight:700;margin-top:4px\">" + value
                + "</div></div>";
    }

    private String duration(Long seconds) {
        if (seconds == null) return "Нет данных";
        Duration duration = Duration.ofSeconds(Math.max(0, seconds));
        long days = duration.toDays();
        long hours = duration.minusDays(days).toHours();
        long minutes = duration.minusDays(days).minusHours(hours).toMinutes();
        if (days > 0) return days + " дн. " + hours + " ч.";
        if (hours > 0) return hours + " ч. " + minutes + " мин.";
        return Math.max(1, minutes) + " мин.";
    }

    private String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
