package app.torrentmanager.settings;

import app.torrentmanager.config.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.*;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;

/** Immutable validated snapshots; a failed write never publishes partial settings. */
@Service
public class RuntimeSettingsService {
    private final Map<String, String> defaults = new LinkedHashMap<>();
    private final List<Class<?>> types;
    private final Path file;
    private final ApplicationEventPublisher events;
    private volatile Snapshot state;

    public RuntimeSettingsService(ManagerProperties manager, EmailNotificationProperties email,
            DownloadStallAlertProperties stall, DailyReportProperties report,
            StorageReportProperties storage, QBitProperties qbit, TorrentAnalyticsProperties analytics,
            Environment environment, ApplicationEventPublisher events) {
        this.events = events;
        var originals = List.of(manager, email, stall, report, storage, qbit, analytics);
        types = originals.stream().map(Object::getClass).toList();
        for (Object original : originals) {
            flatten(prefix(original.getClass()), original, defaults);
        }
        defaults.put("control.manager-enabled", "true");
        for (SettingField field : SettingsCatalog.FIELDS) {
            if (field.key().startsWith("spring.mail.")) {
                defaults.put(field.key(), environment.getProperty(field.key(), ""));
            }
        }
        file = Path.of(environment.getProperty("web.settings-file", "data/settings.properties")).toAbsolutePath();
        Properties stored = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) { stored.load(reader); }
            catch (IOException exception) { throw new IllegalStateException("Не удалось прочитать настройки", exception); }
        }
        Map<String, String> overrides = new LinkedHashMap<>();
        stored.forEach((k, v) -> { if (!k.equals("_revision")) overrides.put(k.toString(), v.toString()); });
        state = build(Long.parseLong(stored.getProperty("_revision", "0")), overrides);
    }

    public <T> T current(Class<T> type) { return type.cast(state.typed().get(type)); }
    public String value(String key) { return state.values().getOrDefault(key, ""); }
    public boolean managerEnabled() { return Boolean.parseBoolean(value("control.manager-enabled")); }
    public boolean notificationsAllowed() {
        return current(EmailNotificationProperties.class).enabled() && !muted();
    }
    public boolean muted() {
        String until = value("control.notifications-muted-until");
        return !until.isBlank() && Instant.now().isBefore(Instant.parse(until));
    }
    public long revision() { return state.revision(); }
    public Map<String, String> valuesSnapshot() { return state.values(); }

    public synchronized View view() {
        Map<String, String> visible = new LinkedHashMap<>();
        Set<String> configuredSecrets = new HashSet<>();
        for (SettingField field : SettingsCatalog.FIELDS) {
            if (field.type().equals("password")) {
                visible.put(field.key(), "");
                if (!value(field.key()).isBlank()) configuredSecrets.add(field.key());
            } else visible.put(field.key(), display(field, value(field.key())));
        }
        return new View(revision(), SettingsCatalog.FIELDS, visible, configuredSecrets,
                current(StorageReportProperties.class).disks().stream()
                        .map(d -> new DiskView(d.name(), d.mountPath().toString(), d.qbitPaths())).toList(),
                value("control.notifications-muted-until"));
    }

    public synchronized View save(Update update) {
        if (update.revision() != revision()) throw new SettingsConflictException();
        Map<String, String> overrides = new LinkedHashMap<>(state.overrides());
        if (update.values() == null) throw new IllegalArgumentException("Настройки не переданы");
        for (var entry : update.values().entrySet()) {
            var field = SettingsCatalog.FIELDS.stream().filter(f -> f.key().equals(entry.getKey()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Неизвестная настройка"));
            if (entry.getValue() == null) throw new IllegalArgumentException("Значение не задано");
            String value = field.type().equals("password") ? entry.getValue() : entry.getValue().strip();
            if (field.type().equals("password") && value.isEmpty()) continue;
            validateField(field, value);
            overrides.put(entry.getKey(), value);
        }
        if ("true".equals(update.values().get("email-notifications.enabled"))) {
            for (String key : List.of("spring.mail.host", "spring.mail.username", "spring.mail.password")) {
                if (overrides.getOrDefault(key, value(key)).isBlank()) throw new IllegalArgumentException("Перед включением уведомлений настройте SMTP-сервер, логин и пароль");
            }
        }
        if (update.disks() != null) {
            if (update.disks().size() > 16) throw new IllegalArgumentException("Максимум 16 дисков");
            overrides.keySet().removeIf(k -> k.startsWith("storage-report.disks["));
            // A count explicitly distinguishes an empty web list from environment defaults.
            overrides.put("control.disk-count", Integer.toString(update.disks().size()));
            for (int i = 0; i < update.disks().size(); i++) {
                var disk = update.disks().get(i);
                if (disk.name() == null || disk.name().isBlank() || disk.mountPath() == null
                        || !disk.mountPath().isAbsolute() || disk.qbitPaths() == null || disk.qbitPaths().isEmpty()
                        || disk.qbitPaths().stream().anyMatch(p -> p == null || !p.startsWith("/") || p.isBlank())) {
                    throw new IllegalArgumentException("Для диска нужны название, абсолютный путь и пути qBittorrent");
                }
                flatten("storage-report.disks[" + i + "]", disk, overrides);
            }
        }
        publish(overrides);
        return view();
    }

    public synchronized View mute(String mode) {
        Map<String, String> overrides = new LinkedHashMap<>(state.overrides());
        switch (mode) {
            case "hour" -> overrides.put("control.notifications-muted-until", Instant.now().plusSeconds(3600).toString());
            case "tomorrow" -> overrides.put("control.notifications-muted-until",
                    LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toString());
            case "off" -> { overrides.put("email-notifications.enabled", "false"); overrides.remove("control.notifications-muted-until"); }
            case "resume" -> { overrides.put("email-notifications.enabled", "true"); overrides.remove("control.notifications-muted-until"); }
            default -> throw new IllegalArgumentException("Неизвестный режим уведомлений");
        }
        if (mode.equals("resume")) {
            for (String key : List.of("spring.mail.host", "spring.mail.username", "spring.mail.password"))
                if (value(key).isBlank()) throw new IllegalArgumentException("Перед включением уведомлений настройте SMTP");
        }
        publish(overrides);
        return view();
    }

    private void publish(Map<String, String> overrides) {
        boolean willSuppress = !Boolean.parseBoolean(overrides.getOrDefault("email-notifications.enabled", value("email-notifications.enabled")));
        String muteUntil = overrides.getOrDefault("control.notifications-muted-until", "");
        willSuppress |= !muteUntil.isEmpty() && Instant.now().isBefore(Instant.parse(muteUntil));
        if (willSuppress) overrides.put("control.notifications-suppressed-before", Instant.now().toString());
        Snapshot candidate = build(revision() + 1, overrides);
        Properties properties = new Properties();
        properties.putAll(overrides);
        properties.put("_revision", Long.toString(candidate.revision()));
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "settings-", ".tmp");
            try {
                restrict(temporary);
                try (Writer writer = Files.newBufferedWriter(temporary)) { properties.store(writer, "Torrent Manager web settings"); }
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException exception) { throw new SettingsPersistenceException(exception); }
        state = candidate;
        try { events.publishEvent(new SettingsChanged(!notificationsAllowed())); }
        catch (RuntimeException exception) {
            // The durable suppression cutoff also protects pending messages if a history write failed.
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Настройки применены; обработчик изменения настроек завершился с ошибкой");
        }
    }

    private Snapshot build(long revision, Map<String, String> overrides) {
        Map<String, String> values = new LinkedHashMap<>(defaults);
        if (overrides.containsKey("control.disk-count")) values.keySet().removeIf(k -> k.startsWith("storage-report.disks["));
        values.putAll(overrides);
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        Map<Class<?>, Object> typed = new HashMap<>();
        for (Class<?> type : types) {
            Object bound;
            try { bound = binder.bind(prefix(type), Bindable.of(type)).get(); }
            catch (RuntimeException exception) { throw new IllegalArgumentException("Некорректные настройки раздела " + prefix(type)); }
            typed.put(type, bound);
        }
        validate(values, typed);
        return new Snapshot(revision, Map.copyOf(overrides), Map.copyOf(values), Map.copyOf(typed));
    }

    private void validate(Map<String, String> values, Map<Class<?>, Object> typed) {
        ManagerProperties m = (ManagerProperties) typed.get(ManagerProperties.class);
        if (m.maxStopsPerCycle() < 1 || m.maxStopsPerCycle() > 100 || m.fairRotationSlots() < 0 || m.fairRotationSlots() > 1000)
            throw new IllegalArgumentException("Недопустимое количество остановок или слотов ротации");
        for (Duration d : List.of(m.slowWindow(), m.limitedSlowWindow(), m.retryCooldown(), m.minimumActionInterval(), m.noSeedsTimeout()))
            if (d.compareTo(Duration.ofSeconds(1)) < 0 || d.compareTo(Duration.ofDays(30)) > 0) throw new IllegalArgumentException("Интервалы должны быть от 1 секунды до 30 дней");
        if (m.minimumDownloadSpeed().isNegative()) throw new IllegalArgumentException("Скорость не может быть отрицательной");
        QBitProperties q = (QBitProperties) typed.get(QBitProperties.class);
        URI uri = q.baseUrl();
        if (uri == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Нужен HTTP(S)-адрес qBittorrent без пароля, параметров и фрагмента");
        if (Boolean.parseBoolean(values.get("spring.mail.properties.mail.smtp.ssl.enable"))
                && Boolean.parseBoolean(values.get("spring.mail.properties.mail.smtp.starttls.enable")))
            throw new IllegalArgumentException("Выберите SSL или STARTTLS");
        int port;
        try { port = Integer.parseInt(values.get("spring.mail.port")); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Некорректный порт SMTP"); }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Порт SMTP должен быть от 1 до 65535");
        if (Boolean.parseBoolean(values.get("storage-report.enabled"))
                && ((StorageReportProperties)typed.get(StorageReportProperties.class)).disks().isEmpty())
            throw new IllegalArgumentException("Добавьте хотя бы один диск для отчёта");
    }

    private static void validateField(SettingField field, String value) {
        if (value.length() > 2048) throw new IllegalArgumentException("Слишком длинное значение");
        if (field.type().equals("boolean") && !List.of("true", "false").contains(value)) throw new IllegalArgumentException("Нужно логическое значение");
        if (field.type().equals("email") && !value.isEmpty() && !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Некорректный адрес почты");
    }

    private static String display(SettingField field, String value) {
        if (field.type().equals("duration")) {
            Duration duration = org.springframework.boot.convert.DurationStyle.detectAndParse(value);
            long seconds = duration.getSeconds();
            if (duration.getNano() == 0) {
                if (seconds != 0 && seconds % 86400 == 0) return seconds / 86400 + "d";
                if (seconds != 0 && seconds % 3600 == 0) return seconds / 3600 + "h";
                if (seconds != 0 && seconds % 60 == 0) return seconds / 60 + "m";
                return seconds + "s";
            }
        }
        if (field.type().equals("size")) {
            long bytes = org.springframework.util.unit.DataSize.parse(value).toBytes();
            return bytes != 0 && bytes % 1024 == 0 ? bytes / 1024 + "KB" : bytes + "B";
        }
        return value;
    }

    private static String prefix(Class<?> type) { return type.getAnnotation(ConfigurationProperties.class).value(); }
    private static void flatten(String key, Object value, Map<String, String> target) {
        if (value == null) return;
        if (value instanceof List<?> list) { for (int i = 0; i < list.size(); i++) flatten(key + "[" + i + "]", list.get(i), target); }
        else if (value.getClass().isRecord()) {
            for (RecordComponent c : value.getClass().getRecordComponents()) {
                try { flatten(key + "." + c.getName().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT), c.getAccessor().invoke(value), target); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            }
        } else target.put(key, value.toString());
    }
    static void restrict(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
    public record Update(long revision, Map<String, String> values, List<StorageReportProperties.Disk> disks) { }
    public record View(long revision, List<SettingField> fields, Map<String, String> values,
            Set<String> configuredSecrets, List<DiskView> disks, String mutedUntil) { }
    public record DiskView(String name, String mountPath, List<String> qbitPaths) { }
    private record Snapshot(long revision, Map<String, String> overrides, Map<String, String> values, Map<Class<?>, Object> typed) { }
    public record SettingsChanged(boolean suppressNotifications) { }
    public static class SettingsConflictException extends RuntimeException { }
    public static class SettingsPersistenceException extends IllegalStateException {
        public SettingsPersistenceException(IOException cause) { super("Не удалось сохранить настройки; изменения не применены", cause); }
    }
}
