package app.torrentmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("email-notifications")
public record EmailNotificationProperties(
        boolean enabled,
        String to,
        Duration workerInterval
) {
    public EmailNotificationProperties {
        if (workerInterval == null || workerInterval.isNegative() || workerInterval.isZero()) {
            throw new IllegalArgumentException("email-notifications.worker-interval должен быть больше нуля");
        }
        if (enabled && isBlank(to)) {
            throw new IllegalArgumentException(
                    "Для почтовых уведомлений необходимо задать EMAIL_TO");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
