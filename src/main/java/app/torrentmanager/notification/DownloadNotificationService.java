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
        String body = """
                Torrent Manager обнаружил завершение загрузки.

                Название: %s
                Hash: %s
                Завершено: %s
                """.formatted(
                record.name(),
                record.hash(),
                DATE_TIME.format(record.completedAt().atZone(ZoneId.systemDefault())));
        return new NotificationMessage("Загрузка завершена — " + record.name(), body);
    }

    private String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
