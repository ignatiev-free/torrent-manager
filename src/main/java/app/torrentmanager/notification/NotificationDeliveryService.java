package app.torrentmanager.notification;

import app.torrentmanager.settings.RuntimeSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
@Primary
public class NotificationDeliveryService implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private static final Duration TELEGRAM_WINDOW = Duration.ofMinutes(5);
    private static final Duration TELEGRAM_TIMEOUT = Duration.ofSeconds(10);

    private final RuntimeSettingsService settings;
    private final TelegramClient telegram;
    private final EmailNotificationSender email;
    private final ObjectMapper json;
    private final Path directory;
    private final Clock clock;
    private final TelegramMode mode;

    @Autowired
    public NotificationDeliveryService(RuntimeSettingsService settings, TelegramClient telegram,
                                       EmailNotificationSender email, ObjectMapper json,
                                       @Value("${telegram-notifications.queue-directory}") Path directory,
                                       TelegramMode mode) {
        this(settings, telegram, email, json, directory, Clock.systemUTC(), mode);
    }

    NotificationDeliveryService(RuntimeSettingsService settings, TelegramClient telegram,
                                EmailNotificationSender email, ObjectMapper json, Path directory, Clock clock) {
        this(settings, telegram, email, json, directory, clock, new TelegramMode(TelegramMode.Mode.AUTONOMOUS));
    }

    NotificationDeliveryService(RuntimeSettingsService settings, TelegramClient telegram,
                                EmailNotificationSender email, ObjectMapper json, Path directory, Clock clock,
                                TelegramMode mode) {
        this.mode = mode;
        this.settings = settings;
        this.telegram = telegram;
        this.email = email;
        this.json = json;
        this.directory = directory;
        this.clock = clock;
    }

    @Override
    public String channel() {
        return mode.integrated() ? "ASSISTANT" : telegramEnabled() ? "TELEGRAM" : email.channel();
    }

    @Override
    public void send(NotificationMessage message) {
        submit(message);
    }

    @Override
    public boolean submit(NotificationMessage message) {
        if (!settings.notificationsAllowed()) {
            throw new EmailNotificationSender.NotificationsSuppressedException();
        }
        if (!mode.integrated() && !telegramEnabled()) {
            email.send(message);
            return false;
        }

        Instant now = clock.instant();
        var pending = new PendingNotification(message, now, now, 0);
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(UUID.randomUUID() + ".json");
            save(file, pending);
            return true;
        } catch (IOException | tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Не удалось сохранить уведомление в очередь");
        }
    }

    @Scheduled(fixedDelayString = "5s")
    void deliverPending() {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.newDirectoryStream(directory, path -> {
            String name = path.getFileName().toString();
            return name.endsWith(".smtp") || name.endsWith(".json");
        })) {
            for (Path file : files) {
                try {
                    Path ready = mode.integrated() && file.toString().endsWith(".json")
                            ? reserveFallback(file) : file;
                    if (ready != null) deliver(ready, json.readValue(ready.toFile(), PendingNotification.class));
                } catch (java.nio.file.NoSuchFileException | java.io.FileNotFoundException removed) {
                    // An ACK may remove an event between directory enumeration and reading.
                } catch (IOException | RuntimeException exception) {
                    log.error("Не удалось обработать уведомление из очереди: {}", file.getFileName());
                }
            }
        } catch (IOException exception) {
            log.error("Не удалось прочитать очередь уведомлений");
        }
    }

    void deliver(Path file, PendingNotification pending) throws IOException {
        String cutoff = settings.value("control.notifications-suppressed-before");
        if (!settings.notificationsAllowed() || (!cutoff.isBlank()
                && !pending.createdAt().isAfter(Instant.parse(cutoff)))) {
            Files.deleteIfExists(file);
            return;
        }

        Instant now = clock.instant();
        Instant deadline = pending.createdAt().plus(TELEGRAM_WINDOW);
        boolean useTelegram = !file.getFileName().toString().endsWith(".smtp")
                && !mode.integrated() && telegramEnabled() && pending.emailFailures() == 0 && now.isBefore(deadline);
        if (now.isBefore(pending.nextAttempt()) && (useTelegram || pending.emailFailures() > 0)) return;

        if (useTelegram) {
            Duration remaining = Duration.between(now, deadline);
            try {
                telegram.send(pending.message().telegramText(),
                        remaining.compareTo(TELEGRAM_TIMEOUT) < 0 ? remaining : TELEGRAM_TIMEOUT);
            } catch (TelegramClient.TelegramDeliveryException exception) {
                save(file, new PendingNotification(pending.message(), pending.createdAt(),
                        clock.instant().plusSeconds(30), 0));
                log.warn("Telegram: {}. Резервная отправка после {}", exception.getMessage(), deadline);
                return;
            }
        } else {
            try {
                email.send(pending.message());
            } catch (EmailNotificationSender.NotificationsSuppressedException exception) {
                Files.deleteIfExists(file);
                return;
            } catch (RuntimeException exception) {
                int failures = pending.emailFailures() + 1;
                if (failures >= 3) {
                    log.error("Резервное письмо не отправлено после {} попыток: {}", failures, file.getFileName());
                    Files.deleteIfExists(file);
                } else {
                    save(file, new PendingNotification(pending.message(), pending.createdAt(),
                            clock.instant().plusSeconds(60L * failures), failures));
                    log.warn("Резервное письмо не отправлено. Попытка {}/3", failures);
                }
                return;
            }
        }
        Files.deleteIfExists(file);
        log.info("Уведомление отправлено через {}", useTelegram ? "Telegram" : "SMTP");
    }

    private synchronized Path reserveFallback(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        var pending = json.readValue(file.toFile(), PendingNotification.class);
        if (clock.instant().isBefore(pending.createdAt().plus(TELEGRAM_WINDOW))) return null;
        Path fallback = file.resolveSibling(file.getFileName().toString().replaceFirst("[.]json$", ".smtp"));
        return Files.move(file, fallback);
    }

    public synchronized Instant claimAssistant(UUID id) throws IOException {
        if (!mode.integrated() || !settings.notificationsAllowed()) return null;
        Path file = directory.resolve(id + ".json");
        if (!Files.exists(file)) return null;
        var pending = json.readValue(file.toFile(), PendingNotification.class);
        String cutoff = settings.value("control.notifications-suppressed-before");
        Instant sendBefore = pending.createdAt().plus(TELEGRAM_WINDOW).minusSeconds(15);
        if (pending.telegramClaimed() || !clock.instant().isBefore(sendBefore)
                || (cutoff != null && !cutoff.isBlank() && !pending.createdAt().isAfter(Instant.parse(cutoff))))
            return null;
        save(file, new PendingNotification(pending.message(), pending.createdAt(), pending.nextAttempt(),
                pending.emailFailures(), true));
        return sendBefore;
    }

    public synchronized java.util.List<Event> pendingForAssistant() throws IOException {
        if (!mode.integrated() || !settings.notificationsAllowed() || !Files.isDirectory(directory)) {
            return java.util.List.of();
        }
        var events = new java.util.ArrayList<Event>();
        String cutoff = settings.value("control.notifications-suppressed-before");
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path path : files) {
                try {
                    UUID id = UUID.fromString(path.getFileName().toString().replaceFirst("\\.json$", ""));
                    var pending = json.readValue(path.toFile(), PendingNotification.class);
                    if (cutoff != null && !cutoff.isBlank() && !pending.createdAt().isAfter(Instant.parse(cutoff))) {
                        continue;
                    }
                    if (pending.telegramClaimed() || !clock.instant().isBefore(pending.createdAt()
                            .plus(TELEGRAM_WINDOW).minusSeconds(15))) continue;
                    events.add(new Event(id, pending.createdAt(), pending.message().telegramText()));
                    if (events.size() == 10) break;
                } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
                    log.warn("Could not read a queued integration notification");
                }
            }
        }
        return java.util.List.copyOf(events);
    }

    public synchronized boolean acknowledgeAssistant(UUID id) throws IOException {
        if (!mode.integrated()) return false;
        Files.deleteIfExists(directory.resolve(id + ".json"));
        return true;
    }

    public record Event(UUID id, Instant createdAt, String text) { }
    private boolean telegramEnabled() {
        return Boolean.parseBoolean(settings.value("telegram-notifications.enabled"));
    }

    private void save(Path file, PendingNotification pending) throws IOException {
        Path temporary = Files.createTempFile(directory, "notification-", ".tmp");
        try {
            json.writeValue(temporary.toFile(), pending);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record PendingNotification(NotificationMessage message, Instant createdAt, Instant nextAttempt, int emailFailures,
                               boolean telegramClaimed) {
        PendingNotification(NotificationMessage message, Instant createdAt, Instant nextAttempt, int emailFailures) {
            this(message, createdAt, nextAttempt, emailFailures, false);
        }
    }
}
