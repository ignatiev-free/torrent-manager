package app.torrentmanager.notification;

import app.torrentmanager.settings.RuntimeSettingsService;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class EmailNotificationSender implements NotificationSender {
    private final RuntimeSettingsService settings;
    public EmailNotificationSender(RuntimeSettingsService settings) { this.settings = settings; }
    @Override public String channel() { return "EMAIL"; }

    public JavaMailSenderImpl configuredSender() {
        var values = settings.valuesSnapshot();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(values.get("spring.mail.host"));
        sender.setPort(Integer.parseInt(values.get("spring.mail.port")));
        sender.setUsername(values.get("spring.mail.username"));
        sender.setPassword(values.get("spring.mail.password"));
        var properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.ssl.enable", settings.value("spring.mail.properties.mail.smtp.ssl.enable"));
        properties.setProperty("mail.smtp.starttls.enable", settings.value("spring.mail.properties.mail.smtp.starttls.enable"));
        properties.setProperty("mail.smtp.starttls.required", settings.value("spring.mail.properties.mail.smtp.starttls.enable"));
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "10000");
        properties.setProperty("mail.smtp.writetimeout", "10000");
        return sender;
    }

    public void testConnection() throws jakarta.mail.MessagingException { configuredSender().testConnection(); }

    @Override public void send(NotificationMessage notification) {
        if (!settings.notificationsAllowed()) throw new NotificationsSuppressedException();
        var sender = configuredSender();
        if (sender.getHost() == null || sender.getHost().isBlank() || sender.getUsername() == null
                || sender.getUsername().isBlank()) throw new IllegalStateException("Настройте SMTP-сервер и отправителя");
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(sender.getUsername());
            helper.setTo(settings.current(app.torrentmanager.config.EmailNotificationProperties.class).to());
            helper.setSubject(notification.subject());
            if (notification.htmlBody() == null) helper.setText(notification.body());
            else helper.setText(notification.body(), notification.htmlBody());
            sender.send(message);
        } catch (jakarta.mail.MessagingException exception) {
            throw new IllegalStateException("Не удалось сформировать письмо", exception);
        }
    }

    public static class NotificationsSuppressedException extends RuntimeException {
        public NotificationsSuppressedException() { super("Уведомления выключены или временно заглушены"); }
    }
}
