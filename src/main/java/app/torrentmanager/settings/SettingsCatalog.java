package app.torrentmanager.settings;

import java.util.List;
import static app.torrentmanager.settings.SettingField.field;

public final class SettingsCatalog {
    private SettingsCatalog() { }
    public static final List<SettingField> FIELDS = List.of(
        field("control.manager-enabled", "Управление очередью", "boolean", "manager", "Пауза отключает действия менеджера, но не останавливает торренты."),
        field("torrent-manager.dry-run", "Режим наблюдения", "boolean", "manager", "Показывать решения в журнале, не меняя состояние торрентов."),
        field("torrent-manager.max-stops-per-cycle", "Заменять за один цикл", "number", "manager", "Количество загрузок, которые менеджер может остановить за проверку."),
        field("torrent-manager.slow-window", "Окно оценки скорости", "duration", "manager", "Например: 5m, 30s, 1h."),
        field("torrent-manager.minimum-download-speed", "Порог медленной загрузки", "size", "manager", "Байт/с, например 400KB. При ограничении канала порог снижается."),
        field("torrent-manager.limited-slow-window", "Окно при ограничении скорости", "duration", "manager", "Более длинное наблюдение при ограниченном канале."),
        field("torrent-manager.minimum-action-interval", "Интервал между действиями", "duration", "manager", "Минимальная пауза между остановками и повторными запусками."),
        field("torrent-manager.retry-cooldown", "Пауза перед повторной попыткой", "duration", "manager", "Время ожидания после остановки менеджером."),
        field("torrent-manager.fair-rotation-slots", "Слоты справедливой ротации", "number", "manager", "Приоритетные повторные попытки для старых загрузок. 0 — выключено."),
        field("torrent-manager.no-seeds-timeout", "Ожидание без сидов", "duration", "manager", "Как долго наблюдать загрузку без доступных сидов."),
        field("email-notifications.enabled", "Почтовые уведомления", "boolean", "notifications", "При отключении ожидающие письма пропускаются и позже не досылаются."),
        field("email-notifications.to", "Получатель", "email", "notifications", "Один адрес для уведомлений и отчётов."),
        field("download-stall-alert.enabled", "Предупреждать об остановке загрузок", "boolean", "notifications", "Одно письмо об инциденте и письмо о восстановлении."),
        field("download-stall-alert.alert-after", "Предупреждать через", "duration", "notifications", "Длительность низкой суммарной скорости до предупреждения."),
        field("download-stall-alert.zero-speed-threshold", "Порог остановки", "size", "notifications", "Скорость в байт/с, например 10KB."),
        field("download-stall-alert.recovery-speed", "Скорость восстановления", "size", "notifications", "Должна быть выше порога остановки."),
        field("download-stall-alert.recovery-window", "Подтверждение восстановления", "duration", "notifications", "Как долго скорость должна оставаться восстановленной."),
        field("daily-report.enabled", "Ежедневный отчёт", "boolean", "reports", "Отправляется, когда почта включена и не заглушена."),
        field("daily-report.time", "Время отправки", "time", "reports", "По часовой зоне контейнера."),
        field("daily-report.max-current-items", "Загрузок в отчёте", "number", "reports", "Максимум активных и ожидающих загрузок."),
        field("daily-report.max-attention-items", "Проблемных торрентов в отчёте", "number", "reports", "Максимальное число объектов, требующих внимания."),
        field("daily-report.manual-cooldown", "Интервал ручных отчётов", "duration", "reports", "Защита от повторной отправки."),
        field("storage-report.enabled", "Диски в отчёте", "boolean", "storage", "Свободное место и прогноз после завершения очереди."),
        field("qbit.base-url", "Адрес qBittorrent", "url", "connections", "Например http://192.168.31.63:8080 или адрес сервиса в Docker-сети."),
        field("qbit.username", "Пользователь qBittorrent", "text", "connections", "Пользователь веб-интерфейса."),
        field("qbit.password", "Пароль qBittorrent", "password", "connections", "Пустое поле сохраняет текущий пароль."),
        field("spring.mail.host", "SMTP-сервер", "text", "mail", "Например smtp.yandex.ru."),
        field("spring.mail.port", "Порт SMTP", "number", "mail", "Обычно 465 для SSL или 587 для STARTTLS."),
        field("spring.mail.username", "Отправитель / логин SMTP", "email", "mail", "Адрес отправителя совпадает с логином."),
        field("spring.mail.password", "Пароль приложения SMTP", "password", "mail", "Пустое поле сохраняет текущий пароль."),
        field("spring.mail.properties.mail.smtp.ssl.enable", "SSL", "boolean", "mail", "Обычно включён для порта 465."),
        field("spring.mail.properties.mail.smtp.starttls.enable", "STARTTLS", "boolean", "mail", "Обычно включён для порта 587.")
    );
}
