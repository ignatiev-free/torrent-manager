package app.torrentmanager.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications/email")
@ConditionalOnProperty(
        name = {"email-notifications.enabled", "email-notifications.test-endpoint-enabled"},
        havingValue = "true")
public class EmailNotificationTestController {
    private final NotificationSender sender;

    public EmailNotificationTestController(NotificationSender sender) {
        this.sender = sender;
    }

    @PostMapping("/test")
    public Map<String, String> sendTestNotification() {
        sender.send(new NotificationMessage(
                "Тестовое уведомление Torrent Manager",
                "SMTP-канал Torrent Manager настроен и работает."));
        return Map.of("status", "sent", "channel", sender.channel());
    }
}
