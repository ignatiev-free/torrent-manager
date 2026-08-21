package app.torrentmanager.notification;

import app.torrentmanager.config.EmailNotificationProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "email-notifications.enabled", havingValue = "true")
public class EmailNotificationSender implements NotificationSender {
    private final JavaMailSender mailSender;
    private final EmailNotificationProperties properties;
    private final String username;

    public EmailNotificationSender(JavaMailSender mailSender,
                                   EmailNotificationProperties properties,
                                   @Value("${spring.mail.username}") String username) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.username = username;
    }

    @Override
    public String channel() {
        return "EMAIL";
    }

    @Override
    public void send(NotificationMessage notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(username);
        message.setTo(properties.to());
        message.setSubject(notification.subject());
        message.setText(notification.body());
        mailSender.send(message);
    }
}
