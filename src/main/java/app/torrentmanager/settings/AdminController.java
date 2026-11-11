package app.torrentmanager.settings;

import app.torrentmanager.analytics.ManagerActionHistoryService;
import app.torrentmanager.monitoring.DownloadCompletionHistoryService;
import app.torrentmanager.notification.EmailNotificationSender;
import app.torrentmanager.qbit.*;
import app.torrentmanager.report.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final RuntimeSettingsService settings;
    private final QBitClient qbit;
    private final StorageCapacityService storage;
    private final DownloadCompletionHistoryService history;
    private final ManagerActionHistoryService actions;
    private final DailyReportService reports;
    private final EmailNotificationSender mail;

    public AdminController(RuntimeSettingsService settings, QBitClient qbit, StorageCapacityService storage,
            DownloadCompletionHistoryService history, ManagerActionHistoryService actions,
            DailyReportService reports, EmailNotificationSender mail) {
        this.settings = settings; this.qbit = qbit; this.storage = storage;
        this.history = history; this.actions = actions; this.reports = reports; this.mail = mail;
    }

    @GetMapping("/settings") public RuntimeSettingsService.View settings() { return settings.view(); }
    @PutMapping("/settings") public RuntimeSettingsService.View save(@RequestBody RuntimeSettingsService.Update update) {
        return settings.save(update);
    }
    @PostMapping("/notifications/{mode}") public RuntimeSettingsService.View notifications(@PathVariable String mode) {
        return settings.mute(mode);
    }
    @PostMapping("/manager/{mode}") public RuntimeSettingsService.View manager(@PathVariable String mode) {
        if (!List.of("pause", "resume").contains(mode)) throw new IllegalArgumentException("Неизвестное действие");
        return settings.save(new RuntimeSettingsService.Update(settings.revision(),
                Map.of("control.manager-enabled", Boolean.toString(mode.equals("resume"))), null));
    }

    @GetMapping("/overview") public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("managerEnabled", settings.managerEnabled());
        result.put("dryRun", settings.current(app.torrentmanager.config.ManagerProperties.class).dryRun());
        result.put("notificationsAllowed", settings.notificationsAllowed());
        result.put("mutedUntil", settings.value("control.notifications-muted-until"));
        result.put("timezone", ZoneId.systemDefault().toString());
        result.put("completions", history.recent(8));
        var recentActions = actions.between(Instant.now().minusSeconds(86400), Instant.now()).reversed();
        result.put("actions", recentActions.stream().limit(12).toList());
        result.put("report", reports.status());
        try {
            var transfer = qbit.getTransferInfo();
            var torrents = qbit.getTorrents();
            result.put("connected", true);
            result.put("downloadSpeed", transfer.downloadSpeed());
            result.put("uploadSpeed", transfer.uploadSpeed());
            result.put("downloadLimit", transfer.downloadRateLimit());
            result.put("uploadLimit", transfer.uploadRateLimit());
            result.put("active", torrents.stream().filter(Torrent::isActivelyDownloading).count());
            result.put("queued", torrents.stream().filter(Torrent::isQueuedForDownload).count());
            result.put("seeding", torrents.stream().filter(t -> List.of("uploading", "stalledUP", "forcedUP").contains(t.state())).count());
        } catch (RuntimeException exception) {
            result.put("connected", false);
            result.put("connectionError", "qBittorrent недоступен. Проверьте адрес, доступ и пароль в разделе подключений.");
        }
        return result;
    }

    @GetMapping("/storage") public StorageCapacitySnapshot storage() { return storage.snapshot(); }

    @GetMapping("/queue") public QueueState queue() {
        synchronized (qbit) { return new QueueState(qbit.getQueuePreferences(), qbit.alternativeSpeedMode()); }
    }

    @PutMapping("/queue") public QueueState queue(@RequestBody QueueUpdate update) {
        if (update.expected() == null || update.desired() == null || update.desired().preferences() == null)
            throw new IllegalArgumentException("Передайте исходные и новые лимиты");
        update.desired().preferences().validate();
        synchronized (qbit) {
            if (!queue().equals(update.expected())) throw new RuntimeSettingsService.SettingsConflictException();
            qbit.setQueuePreferences(update.desired().preferences());
            qbit.setAlternativeSpeedMode(update.desired().alternativeMode());
            QueueState actual = queue();
            if (!actual.equals(update.desired())) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "qBittorrent применил не все значения. Обновите лимиты и проверьте результат.");
            return actual;
        }
    }

    @PostMapping("/test/qbittorrent") public Map<String, String> testQbit() {
        qbit.getQueuePreferences();
        return Map.of("message", "Соединение с qBittorrent установлено");
    }
    @PostMapping("/test/smtp") public Map<String, String> testMail() {
        try { mail.testConnection(); }
        catch (jakarta.mail.MessagingException exception) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Не удалось подключиться к SMTP. Проверьте сервер, порт и учётные данные."); }
        return Map.of("message", "SMTP доступен. Проверка выполнена без отправки письма.");
    }
    @PostMapping("/reports/send") public Map<String, String> report() {
        reports.sendManual();
        return Map.of("message", "Отчёт отправлен");
    }

    @GetMapping("/reports/status") public ReportStatusResponse reportStatus() { return reports.status(); }

    @ExceptionHandler(RuntimeSettingsService.SettingsConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict() { return Map.of("message", "Настройки уже изменились. Обновите страницу раздела перед сохранением."); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException exception) { return Map.of("message", exception.getMessage()); }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> unavailable() { return Map.of("message", "Действие недоступно. Проверьте настройки, состояние уведомлений и интервал отчётов."); }

    @ExceptionHandler(DailyReportService.ReportCooldownException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> reportCooldown(DailyReportService.ReportCooldownException exception) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(exception.retryAfterSeconds()))
                .body(Map.of("message", exception.getMessage(), "nextAllowedAt", exception.nextAllowedAt(),
                        "retryAfterSeconds", exception.retryAfterSeconds()));
    }

    @ExceptionHandler(DailyReportService.ReportUnavailableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> reportUnavailable(DailyReportService.ReportUnavailableException exception) {
        return Map.of("message", exception.getMessage());
    }

    @ExceptionHandler(RuntimeSettingsService.SettingsPersistenceException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> persistenceFailure() { return Map.of("message", "Не удалось сохранить настройки. Действующие значения не изменены. Проверьте доступ к каталогу data."); }

    @ExceptionHandler(org.springframework.mail.MailException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> mailFailure() { return Map.of("message", "Не удалось отправить письмо. Проверьте SMTP-соединение и настройки почты."); }

    @ExceptionHandler(org.springframework.web.client.RestClientException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> connectionFailure() { return Map.of("message", "qBittorrent не ответил. Если вы сохраняли лимиты, обновите их и проверьте результат перед повтором."); }

    public record QueueState(QueuePreferences preferences, boolean alternativeMode) { }
    public record QueueUpdate(QueueState expected, QueueState desired) { }
}
