package app.torrentmanager.report;

import app.torrentmanager.analytics.ManagerActionEvent;
import app.torrentmanager.analytics.ManagerActionHistoryService;
import app.torrentmanager.analytics.TorrentAnalyticsRecord;
import app.torrentmanager.analytics.TorrentAnalyticsService;
import app.torrentmanager.config.DailyReportProperties;
import app.torrentmanager.monitoring.DownloadCompletionHistoryService;
import app.torrentmanager.monitoring.DownloadCompletionRecord;
import app.torrentmanager.notification.NotificationMessage;
import app.torrentmanager.notification.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DailyReportService {
    private static final Logger log = LoggerFactory.getLogger(DailyReportService.class);
    private static final DateTimeFormatter SUBJECT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private final DownloadCompletionHistoryService completionHistory;
    private final TorrentAnalyticsService analyticsService;
    private final ManagerActionHistoryService actionHistory;
    private final StorageCapacityService storageCapacityService;
    private final DailyReportFormatter formatter;
    private final DailyReportStateStore stateStore;
    private final NotificationSender sender;
    private final DailyReportProperties properties;
    @org.springframework.beans.factory.annotation.Autowired
    private app.torrentmanager.settings.RuntimeSettingsService runtimeSettings;

    private DailyReportProperties properties() {
        return runtimeSettings == null ? properties : runtimeSettings.current(DailyReportProperties.class);
    }
    private final Clock clock;
    private DailyReportState state;

    @Autowired
    public DailyReportService(DownloadCompletionHistoryService completionHistory,
                              TorrentAnalyticsService analyticsService,
                              ManagerActionHistoryService actionHistory,
                              StorageCapacityService storageCapacityService,
                              DailyReportFormatter formatter,
                              DailyReportStateStore stateStore,
                              NotificationSender sender,
                              DailyReportProperties properties) {
        this(completionHistory, analyticsService, actionHistory, storageCapacityService,
                formatter, stateStore,
                sender, properties, Clock.systemDefaultZone());
    }

    DailyReportService(DownloadCompletionHistoryService completionHistory,
                       TorrentAnalyticsService analyticsService,
                       ManagerActionHistoryService actionHistory,
                       StorageCapacityService storageCapacityService,
                       DailyReportFormatter formatter, DailyReportStateStore stateStore,
                       NotificationSender sender, DailyReportProperties properties, Clock clock) {
        this.completionHistory = completionHistory;
        this.analyticsService = analyticsService;
        this.actionHistory = actionHistory;
        this.storageCapacityService = storageCapacityService;
        this.formatter = formatter;
        this.stateStore = stateStore;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
        this.state = stateStore.load().orElse(DailyReportState.empty());
    }

    @Scheduled(fixedDelayString = "${daily-report.poll-interval:1m}",
            initialDelayString = "${daily-report.initial-delay:30s}")
    public synchronized void sendScheduledIfDue() {
        if (!properties().enabled() || (runtimeSettings != null && !runtimeSettings.notificationsAllowed())) return;
        ZonedDateTime now = ZonedDateTime.now(clock);
        if (now.toLocalTime().isBefore(properties().time())
                || now.toLocalDate().equals(state.lastDailyReportDate())) {
            return;
        }
        try {
            sendDaily(now);
        } catch (RuntimeException exception) {
            recordFailure(exception);
            log.warn("Не удалось отправить ежедневный отчёт: {}", safeError(exception));
        }
    }

    public synchronized void sendManual() {
        if (runtimeSettings != null && !runtimeSettings.notificationsAllowed()) {
            throw new ReportUnavailableException("Уведомления выключены или временно заглушены. Включите их перед отправкой отчёта.");
        }
        Instant now = clock.instant();
        if (state.lastManualReportAt() != null
                && now.isBefore(state.lastManualReportAt().plus(properties().manualCooldown()))) {
            throw cooldown(now);
        }
        try {
            Instant from = reportFrom(now);
            sender.send(buildMessage("Torrent Manager — внеплановый отчёт на "
                    + SUBJECT_DATE.format(now.atZone(clock.getZone())), from, now));
            state = new DailyReportState(state.lastDailyReportDate(), state.lastDailyReportAt(),
                    now, "DELIVERED", null, state.progressBaseline());
            stateStore.save(state);
            log.info("Внеплановый отчёт отправлен (канал={})", sender.channel());
        } catch (RuntimeException exception) {
            recordFailure(exception);
            throw exception;
        }
    }

    public synchronized ReportStatusResponse status() {
        Instant now = clock.instant();
        Instant next = state.lastManualReportAt() == null ? null
                : state.lastManualReportAt().plus(properties().manualCooldown());
        boolean mailAllowed = runtimeSettings == null || runtimeSettings.notificationsAllowed();
        boolean waiting = next != null && now.isBefore(next);
        String reason = !mailAllowed ? "Уведомления выключены или временно заглушены"
                : waiting ? cooldown(now).getMessage() : null;
        return new ReportStatusResponse(formatInstant(state.lastDailyReportAt()),
                formatInstant(state.lastManualReportAt()), state.lastDeliveryStatus(), state.lastError(),
                next, mailAllowed && !waiting, reason);
    }

    private ReportCooldownException cooldown(Instant now) {
        Instant next = state.lastManualReportAt().plus(properties().manualCooldown());
        var remaining = java.time.Duration.between(now, next);
        long seconds = Math.max(1, remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0));
        String time = DateTimeFormatter.ofPattern("HH:mm:ss").format(next.atZone(clock.getZone()));
        return new ReportCooldownException("Отчёт уже отправлен. Повторная отправка доступна в "
                + time + " (" + clock.getZone() + ").", next, seconds);
    }

    public static class ReportCooldownException extends IllegalStateException {
        private final Instant nextAllowedAt;
        private final long retryAfterSeconds;
        public ReportCooldownException(String message, Instant nextAllowedAt, long retryAfterSeconds) {
            super(message); this.nextAllowedAt = nextAllowedAt; this.retryAfterSeconds = retryAfterSeconds;
        }
        public Instant nextAllowedAt() { return nextAllowedAt; }
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    public static class ReportUnavailableException extends IllegalStateException {
        public ReportUnavailableException(String message) { super(message); }
    }

    private void sendDaily(ZonedDateTime now) {
        Instant to = now.toInstant();
        Instant from = reportFrom(to);
        List<TorrentAnalyticsRecord> analytics = analyticsService.snapshot();
        sender.send(buildMessage("Torrent Manager — ежедневный отчёт за "
                + now.toLocalDate(), from, to));
        Map<String, Double> baseline = new LinkedHashMap<>();
        analytics.forEach(record -> baseline.put(record.hash(), record.progress()));
        state = new DailyReportState(now.toLocalDate(), to, state.lastManualReportAt(),
                "DELIVERED", null, baseline);
        stateStore.save(state);
        log.info("Ежедневный отчёт отправлен за {} (канал={})", now.toLocalDate(), sender.channel());
    }

    private NotificationMessage buildMessage(String subject, Instant from, Instant to) {
        List<DownloadCompletionRecord> completions = completionHistory.recent(1000).stream()
                .filter(record -> !record.completedAt().isBefore(from))
                .filter(record -> record.completedAt().isBefore(to))
                .toList();
        List<TorrentAnalyticsRecord> analytics = analyticsService.snapshot();
        List<ManagerActionEvent> actions = actionHistory.between(from, to);
        StorageCapacitySnapshot storage = storageCapacityService.snapshot();
        String text = formatter.format(from, to, completions, analytics, actions,
                state.progressBaseline(), storage);
        String html = formatter.formatHtml(from, to, completions, analytics, actions,
                state.progressBaseline(), storage);
        return new NotificationMessage(subject, text, html);
    }

    private Instant reportFrom(Instant now) {
        return state.lastDailyReportAt() == null ? now.minusSeconds(24 * 60 * 60)
                : state.lastDailyReportAt();
    }

    private void recordFailure(RuntimeException exception) {
        state = new DailyReportState(state.lastDailyReportDate(), state.lastDailyReportAt(),
                state.lastManualReportAt(), "FAILED", safeError(exception), state.progressBaseline());
        stateStore.save(state);
    }

    private String formatInstant(Instant instant) {
        return instant == null ? "ещё не отправлялся"
                : SUBJECT_DATE.format(instant.atZone(clock.getZone()));
    }

    private String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
