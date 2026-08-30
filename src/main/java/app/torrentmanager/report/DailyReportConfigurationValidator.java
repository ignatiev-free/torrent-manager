package app.torrentmanager.report;

import app.torrentmanager.config.EmailNotificationProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "daily-report.enabled", havingValue = "true")
public class DailyReportConfigurationValidator implements ApplicationRunner {
    private final EmailNotificationProperties emailProperties;

    public DailyReportConfigurationValidator(EmailNotificationProperties emailProperties) {
        this.emailProperties = emailProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!emailProperties.enabled()) {
            throw new IllegalStateException("DAILY_REPORT_ENABLED=true требует "
                    + "EMAIL_NOTIFICATIONS_ENABLED=true и настроенного SMTP");
        }
    }
}
