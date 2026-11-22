package app.torrentmanager.notification;

import app.torrentmanager.analytics.TorrentAnalyticsService;
import app.torrentmanager.manager.ManagerStateStore;
import app.torrentmanager.monitoring.DownloadCompletionHistoryService;
import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.Torrent;
import app.torrentmanager.report.DailyReportService;
import app.torrentmanager.report.StorageCapacityService;
import app.torrentmanager.settings.RuntimeSettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static app.torrentmanager.notification.TelegramClient.Button;

@Component
class TelegramCommandService {
    private static final Logger log = LoggerFactory.getLogger(TelegramCommandService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final DateTimeFormatter COMPLETION_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final List<List<Button>> MENU = List.of(
            List.of(new Button("⚡ Управление скоростью", "speed")),
            List.of(new Button("📊 Отчёты", "reports")));
    private static final List<List<Button>> REPORT_MENU = List.of(
            List.of(new Button("📊 Общая сводка (внеплановая)", "report")),
            List.of(new Button("⬇️ В загрузке сейчас", "current")),
            List.of(new Button("✅ Завершено за 24 часа", "downloads")),
            List.of(new Button("💾 Место на дисках", "storage")),
            List.of(new Button("↩️ Меню", "menu")));
    private static final List<List<Button>> CURRENT_MENU = List.of(
            List.of(new Button("🔄 Обновить", "current:refresh"), new Button("↩ Отчёты", "reports")));
    private static final List<Button> SPEEDS = List.of(
            new Button("🚀 Максимальная", "speed:max"),
            new Button("🌙 Рабочая · 8 000 КиБ/с", "speed:work"),
            new Button("💻 Минимальная · 3 000 КиБ/с", "speed:min"));

    private final TelegramClient telegram;
    private final RuntimeSettingsService settings;
    private final QBitClient qbit;
    private final DailyReportService reports;
    private final DownloadCompletionHistoryService history;
    private final StorageCapacityService storage;
    private final TorrentAnalyticsService analytics;
    private final ManagerStateStore managerState;
    private final Path cursor;
    private final Path speedTimer;
    private Thread poller;

    TelegramCommandService(TelegramClient telegram, RuntimeSettingsService settings, QBitClient qbit,
                           DailyReportService reports, DownloadCompletionHistoryService history,
                           StorageCapacityService storage, TorrentAnalyticsService analytics,
                           ManagerStateStore managerState,
                           @Value("${telegram-notifications.command-state-file}") Path cursor) {
        this.telegram = telegram;
        this.settings = settings;
        this.qbit = qbit;
        this.reports = reports;
        this.history = history;
        this.storage = storage;
        this.analytics = analytics;
        this.managerState = managerState;
        this.cursor = cursor.toAbsolutePath();
        this.speedTimer = this.cursor.resolveSibling("telegram-speed-timer");
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        poller = Thread.ofVirtual().name("telegram-commands").start(this::poll);
    }

    @PreDestroy
    void stop() {
        if (poller != null) poller.interrupt();
    }

    private void poll() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                var config = settings.valuesSnapshot();
                if (!enabled(config)) {
                    Thread.sleep(2000);
                    continue;
                }
                pollOnce(config);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException | IOException exception) {
                if (Thread.currentThread().isInterrupted()) return;
                log.warn("Не удалось получить команды Telegram: {}", exception instanceof TelegramClient.TelegramDeliveryException
                        ? exception.getMessage() : exception.getClass().getSimpleName());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void pollOnce(Map<String, String> config) throws IOException {
        String token = config.get("telegram-notifications.bot-token");
        String identity = token.substring(0, token.indexOf(':'));
        long offset = readOffset(identity);
        for (JsonNode update : telegram.updates(config, offset)) {
            if (Thread.currentThread().isInterrupted() || !stillAuthorized(config)) return;
            long id = update.path("update_id").asLong(-1);
            if (id < 0 || (offset >= 0 && id < offset)) continue;
            offset = id + 1;
            // Persist before acting. A crash can drop the current command, but cannot replay it.
            saveOffset(identity, offset);
            handle(config, update);
        }
    }

    private long readOffset(String identity) throws IOException {
        if (!Files.exists(cursor) || Files.getLastModifiedTime(cursor).toInstant()
                .isBefore(Instant.now().minus(Duration.ofDays(6)))) return -1;
        String[] fields = Files.readString(cursor).strip().split(" ", 2);
        return fields.length == 2 && identity.equals(fields[0]) ? Long.parseLong(fields[1]) : -1;
    }

    private void saveOffset(String identity, long offset) throws IOException {
        Files.createDirectories(cursor.getParent());
        Path temp = Files.createTempFile(cursor.getParent(), "telegram-offset-", ".tmp");
        try {
            Files.writeString(temp, identity + " " + offset);
            Files.move(temp, cursor, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean enabled(Map<String, String> config) {
        return Boolean.parseBoolean(config.get("telegram-notifications.enabled"))
                && Boolean.parseBoolean(config.get("telegram-notifications.commands-enabled"));
    }

    private boolean stillAuthorized(Map<String, String> config) {
        var current = settings.valuesSnapshot();
        return enabled(current)
                && config.get("telegram-notifications.chat-id").equals(current.get("telegram-notifications.chat-id"))
                && config.get("telegram-notifications.bot-token").equals(current.get("telegram-notifications.bot-token"));
    }

    void handle(Map<String, String> config, JsonNode update) {
        var callback = update.path("callback_query");
        boolean button = !callback.isMissingNode();
        var message = button ? callback.path("message") : update.path("message");
        var chat = message.path("chat");
        String allowed = config.get("telegram-notifications.chat-id");
        if (!"private".equals(chat.path("type").asText()) || !allowed.equals(chat.path("id").asText())
                || !allowed.equals((button ? callback : message).path("from").path("id").asText())) return;

        String command = button ? callback.path("data").asText() : message.path("text").asText().strip();
        if (button) {
            try {
                telegram.acknowledge(config, callback.path("id").asText());
            } catch (TelegramClient.TelegramDeliveryException exception) {
                log.debug("Telegram не подтвердил закрытие индикатора кнопки");
            }
        }
        if (Thread.currentThread().isInterrupted() || !stillAuthorized(config)) return;
        try {
            switch (command) {
                case "/start", "/help", "/menu", "menu" -> send(config, mainMenu(), MENU);
                case "/speed", "speed" -> speed(config, null, null);
                case "speed:max", "speed:work", "speed:min" -> {
                    if (button) send(config, "⏱ На сколько изменить скорость?", List.of(
                            List.of(new Button("На 1 час", command + ":hour"), new Button("До утра", command + ":morning")),
                            List.of(new Button("До следующего изменения", command + ":manual")),
                            List.of(new Button("↩ Скорость", "speed"))));
                }
                case "speed:max:hour", "speed:work:hour", "speed:min:hour",
                     "speed:max:morning", "speed:work:morning", "speed:min:morning",
                     "speed:max:manual", "speed:work:manual", "speed:min:manual" -> {
                    if (button) {
                        int separator = command.lastIndexOf(':');
                        speed(config, command.substring(0, separator), command.substring(separator + 1));
                    }
                }
                case "reports" -> send(config, "📊 Отчёты", REPORT_MENU);
                case "/report", "report" -> {
                    boolean queued = reports.sendManual();
                    send(config, queued ? "📨 Отчёт в очереди\n\nОтправим в Telegram или резервным письмом."
                            : "✅ Отчёт отправлен по почте", REPORT_MENU);
                }
                case "/current", "current" -> send(config, currentDownloads(), CURRENT_MENU);
                case "current:refresh" -> {
                    long messageId = message.path("message_id").asLong();
                    if (button && messageId > 0 && stillAuthorized(config)) {
                        telegram.edit(config, currentDownloads(), CURRENT_MENU, REQUEST_TIMEOUT, messageId);
                    }
                }
                case "/downloads", "downloads" -> send(config, downloads(), REPORT_MENU);
                case "/storage", "storage" -> send(config, disks(), REPORT_MENU);
                default -> {
                    if (command.startsWith("/")) send(config, "Открой меню команд через /menu.", MENU);
                }
            }
        } catch (DailyReportService.ReportCooldownException exception) {
            send(config, "⏳ Отчёт уже запрошен\n\n" + exception.getMessage(), REPORT_MENU);
        } catch (DailyReportService.ReportUnavailableException exception) {
            send(config, "⚠️ Отчёт недоступен\n\n" + exception.getMessage(), REPORT_MENU);
        } catch (TelegramClient.TelegramDeliveryException exception) {
            log.warn("Ответ на команду Telegram: {}", exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("Команда Telegram завершилась ошибкой: {}", exception.getClass().getSimpleName());
            send(config, "⚠️ Команда не выполнена\n\nПроверь состояние сервиса и повтори.", MENU);
        }
    }

    private void speed(Map<String, String> config, String action, String period) {
        long desired = action == null ? -1 : switch (action) {
            case "speed:work" -> 8000 * 1024L;
            case "speed:min" -> 3000 * 1024L;
            default -> 0;
        };
        app.torrentmanager.qbit.TransferInfo transfer;
        SpeedTimer timer;
        synchronized (qbit) {
            if (desired >= 0) {
                if (!stillAuthorized(config)) return;
                applySpeed(desired, period);
            }
            transfer = qbit.getTransferInfo();
            timer = readSpeedTimer();
        }
        long actual = transfer.downloadRateLimit();
        String title = desired < 0 ? "⚡ Управление скоростью"
                : desired == actual ? "✅ Лимит скачивания применён" : "⚠️ qBittorrent вернул другой лимит";
        String text = title + "\n\n🎚 Лимит: " + (actual <= 0 ? "без лимита" : speedText(actual))
                + "\n⚡ Текущая скорость: " + speedText(transfer.downloadSpeed());
        if (timer != null && timer.applied() == actual) {
            text += "\n⏱ Возврат лимита: " + COMPLETION_TIME.format(timer.until());
        }
        var buttons = new java.util.ArrayList<List<Button>>();
        long[] limits = {0, 8000 * 1024L, 3000 * 1024L};
        for (int i = 0; i < SPEEDS.size(); i++) {
            var option = SPEEDS.get(i);
            buttons.add(List.of(new Button((actual == limits[i] ? "✓ " : "") + option.label(), option.action())));
        }
        buttons.add(List.of(new Button("↩️ Меню", "menu")));
        send(config, text, buttons);
    }

    private void applySpeed(long desired, String period) {
        boolean alternative = qbit.alternativeSpeedMode();
        long current = qbit.getTransferInfo().downloadRateLimit();
        if ("manual".equals(period)) {
            qbit.setDownloadLimit(desired, alternative);
            clearSpeedTimer();
            return;
        }

        Instant now = Instant.now();
        Instant until = now.plus(Duration.ofHours(1));
        if ("morning".equals(period)) {
            var local = now.atZone(ZoneId.systemDefault());
            var morning = local.toLocalDate().atTime(8, 0).atZone(local.getZone());
            until = (morning.isAfter(local) ? morning : morning.plusDays(1)).toInstant();
        }
        SpeedTimer existing = readSpeedTimer();
        long previous = existing != null && existing.alternative() == alternative && existing.applied() == current
                ? existing.previous() : current;
        saveSpeedTimer(new SpeedTimer(previous, desired, alternative, until));
        qbit.setDownloadLimit(desired, alternative);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15000)
    void restoreSpeed() {
        synchronized (qbit) {
            try {
                SpeedTimer timer = readSpeedTimer();
                if (timer == null) return;
                boolean alternative = qbit.alternativeSpeedMode();
                long current = qbit.getTransferInfo().downloadRateLimit();
                if (alternative != timer.alternative() || current != timer.applied()) {
                    clearSpeedTimer();
                } else if (!Instant.now().isBefore(timer.until())) {
                    qbit.setDownloadLimit(timer.previous(), alternative);
                    if (qbit.getTransferInfo().downloadRateLimit() == timer.previous()) clearSpeedTimer();
                }
            } catch (RuntimeException exception) {
                log.warn("Не удалось проверить таймер скорости: {}", exception.getClass().getSimpleName());
            }
        }
    }

    private SpeedTimer readSpeedTimer() {
        try {
            if (!Files.exists(speedTimer)) return null;
            String[] fields = Files.readString(speedTimer).strip().split(" ");
            return new SpeedTimer(Long.parseLong(fields[0]), Long.parseLong(fields[1]),
                    Boolean.parseBoolean(fields[2]), Instant.parse(fields[3]));
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private void saveSpeedTimer(SpeedTimer timer) {
        try {
            Files.createDirectories(speedTimer.getParent());
            Path temp = Files.createTempFile(speedTimer.getParent(), "telegram-speed-", ".tmp");
            try {
                Files.writeString(temp, timer.previous() + " " + timer.applied() + " "
                        + timer.alternative() + " " + timer.until());
                Files.move(temp, speedTimer, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private void clearSpeedTimer() {
        try {
            Files.deleteIfExists(speedTimer);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private record SpeedTimer(long previous, long applied, boolean alternative, Instant until) { }

    private String mainMenu() {
        String title = "⚡ Torrent Manager";
        try {
            var transfer = qbit.getTransferInfo();
            var active = qbit.getTorrents().stream().filter(Torrent::isActivelyDownloading).toList();
            long downloading = active.stream().filter(torrent -> torrent.downloadSpeed() > 0).count();
            long limit = transfer.downloadRateLimit();
            return title + "\n\n⬇️ Качаются: " + downloading
                    + "\n⏸ Активны без передачи: " + (active.size() - downloading)
                    + "\n⚡ Текущая скорость: " + speedText(transfer.downloadSpeed())
                    + "\n🎚 Лимит: " + (limit <= 0 ? "без лимита" : speedText(limit));
        } catch (RuntimeException exception) {
            log.warn("Не удалось получить сводку qBittorrent для меню: {}", exception.getClass().getSimpleName());
            return title + "\n\n⚠️ Данные qBittorrent сейчас недоступны.";
        }
    }

    private String currentDownloads() {
        Instant now = Instant.now();
        var torrents = qbit.getTorrents();
        var active = torrents.stream().filter(Torrent::isActivelyDownloading)
                .sorted(Comparator.comparingLong(Torrent::downloadSpeed).reversed()).toList();
        var retryTimes = managerState.load();
        var paused = torrents.stream().filter(torrent -> !torrent.isComplete()
                        && ("stoppedDL".equals(torrent.state()) || "pausedDL".equals(torrent.state()))
                        && retryTimes.containsKey(torrent.hash()))
                .sorted(Comparator.comparing(torrent -> retryTimes.get(torrent.hash()))).toList();
        if (active.isEmpty() && paused.isEmpty()) return "⬇️ В загрузке сейчас\n\nАктивных загрузок нет.";
        long downloading = active.stream().filter(torrent -> torrent.downloadSpeed() > 0).count();
        var text = new StringBuilder("⬇️ В загрузке сейчас\n\nКачаются: ").append(downloading)
                .append(" · без передачи: ").append(active.size() - downloading)
                .append("\nОбщая скорость: ").append(speedText(active.stream().mapToLong(Torrent::downloadSpeed).sum()));
        if (downloading > 0) {
            text.append("\n\nКачаются");
            active.stream().filter(torrent -> torrent.downloadSpeed() > 0)
                    .limit(6).forEach(torrent -> appendCurrentTorrent(text, torrent, now));
            if (downloading > 6) text.append("\nИ ещё ").append(downloading - 6);
        }
        long idle = active.size() - downloading;
        if (idle > 0) {
            var progressTimes = new java.util.HashMap<String, Instant>();
            analytics.snapshot().forEach(record -> progressTimes.put(record.hash(), record.lastProgressAt()));
            text.append("\n\nАктивны без передачи");
            active.stream().filter(torrent -> torrent.downloadSpeed() <= 0)
                    .limit(6).forEach(torrent -> {
                        appendCurrentTorrent(text, torrent, now);
                        text.append("\n   Сиды: ").append(Math.max(0, torrent.connectedSeeds()))
                                .append(" подключено · ")
                                .append(torrent.availableSeeds() < 0 ? "всего неизвестно"
                                        : torrent.availableSeeds() + " известно");
                        var lastProgress = progressTimes.get(torrent.hash());
                        if (lastProgress != null && !lastProgress.isAfter(now)) {
                            text.append("\n   Без прогресса по наблюдениям: ")
                                    .append(duration(Duration.between(lastProgress, now)));
                        }
                    });
            if (idle > 6) text.append("\nИ ещё ").append(idle - 6);
        }
        if (!paused.isEmpty()) {
            text.append("\n\nПауза менеджера");
            paused.stream().limit(4).forEach(torrent -> {
                Instant eligibleAt = retryTimes.get(torrent.hash());
                text.append("\n\n• ").append(shortName(torrent.name())).append("\n└ ");
                if (eligibleAt.isAfter(now)) {
                    text.append("Повтор возможен с ").append(COMPLETION_TIME.format(eligibleAt));
                } else {
                    text.append("Ожидает повторной попытки");
                }
            });
            if (paused.size() > 4) text.append("\nИ ещё ").append(paused.size() - 4);
        }
        return text.toString();
    }

    private static void appendCurrentTorrent(StringBuilder text, Torrent torrent, Instant now) {
        text.append("\n\n• ").append(shortName(torrent.name()))
                .append("\n").append(TelegramMessageFormat.progress(torrent.progress()))
                .append("\n├ Скорость: ").append(speedText(torrent.downloadSpeed()));
        if (torrent.downloadSpeed() > 0 && torrent.amountLeft() > 0) {
            long eta = Math.ceilDiv(torrent.amountLeft(), torrent.downloadSpeed());
            text.append("\n├ Осталось: ≈ ").append(duration(Duration.ofSeconds(eta)));
        }
        text.append("\n└ ").append(timeSinceAdded(torrent, now));
    }

    private static String timeSinceAdded(Torrent torrent, Instant now) {
        if (torrent.addedOn() <= 0 || torrent.addedOn() > now.getEpochSecond()) return "время добавления неизвестно";
        return "С добавления: " + duration(Duration.between(Instant.ofEpochSecond(torrent.addedOn()), now));
    }

    private static String duration(Duration elapsed) {
        if (elapsed.toSeconds() < 60) return "меньше минуты";
        long days = elapsed.toDays();
        return (days > 0 ? days + " д. " : "") + elapsed.toHoursPart() + " ч. "
                + elapsed.toMinutesPart() + " мин.";
    }

    private String downloads() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        var completions = history.recent(Integer.MAX_VALUE).stream()
                .filter(item -> !item.completedAt().isBefore(cutoff)).toList();
        var text = new StringBuilder("✅ Загрузки за 24 часа\n\nЗавершено: ").append(completions.size());
        completions.stream().limit(20).forEach(item -> text.append("\n\n• ")
                .append(shortName(item.name())).append("\n└ Завершено: ")
                .append(COMPLETION_TIME.format(item.completedAt())));
        if (completions.size() > 20) text.append("\nИ ещё ").append(completions.size() - 20);
        return text.toString();
    }

    private String disks() {
        var snapshot = storage.snapshot();
        if (!snapshot.enabled()) return "💾 Отчёт по дискам выключен в настройках.";
        if (snapshot.error() != null) return "💾 Не удалось получить данные о дисках.";
        if (snapshot.disks().isEmpty()) return "💾 Место на дисках\n\nНет данных по дискам.";
        var text = new StringBuilder("💾 Место на дисках");
        for (var disk : snapshot.disks()) {
            text.append("\n\n• ").append(shortName(disk.name()));
            if (disk.available()) {
                text.append("\n├ Свободно: ").append(size(disk.freeBytes()))
                        .append("\n├ Осталось скачать: ").append(size(disk.remainingBytes()));
                long projected = disk.projectedFreeBytes();
                text.append(projected < 0 ? "\n⚠️ Не хватит: " : "\nПосле загрузок: ")
                        .append(size(Math.abs(projected)));
            } else {
                text.append("\nДанные о свободном месте недоступны");
            }
            if (disk.unknownSizeTorrents() > 0) {
                text.append("\nБез известного размера: ").append(disk.unknownSizeTorrents());
            }
        }
        if (snapshot.unmatchedTorrents() > 0) {
            text.append("\n\nБез привязки к диску: ").append(snapshot.unmatchedTorrents())
                    .append(" · ").append(size(snapshot.unmatchedRemainingBytes()));
        }
        return text.toString();
    }

    private static String shortName(String name) {
        String clean = name.replace('\n', ' ').replace('\r', ' ');
        if (clean.length() <= 120) return clean;
        int end = Character.isHighSurrogate(clean.charAt(118)) ? 118 : 119;
        return clean.substring(0, end) + "…";
    }

    private static String speedText(long bytesPerSecond) {
        return String.format(Locale.ROOT, "%,.0f КиБ/с", bytesPerSecond / 1024.0).replace(',', ' ');
    }

    private static String size(long bytes) {
        long gib = 1024L * 1024 * 1024;
        return bytes >= gib
                ? bytes >= gib * 1024
                    ? String.format(Locale.ROOT, "%.2f ТиБ", (double) bytes / (gib * 1024))
                    : String.format(Locale.ROOT, "%.2f ГиБ", (double) bytes / gib)
                : String.format(Locale.ROOT, "%.1f МиБ", bytes / (1024.0 * 1024));
    }

    private void send(Map<String, String> config, String text, List<List<Button>> buttons) {
        if (stillAuthorized(config)) telegram.send(config, text, buttons, REQUEST_TIMEOUT);
    }
}
