package app.torrentmanager.notification;

import app.torrentmanager.integration.IntegrationSpeedController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "torrent-manager.telegram-mode=INTEGRATED",
        "telegram-notifications.bot-token=",
        "telegram-notifications.commands-enabled=true",
        "telegram-notifications.enabled=true",
        "traffic-stats.initial-delay=1h",
        "download-monitor.initial-delay=1h",
        "torrent-analytics.initial-delay=1h",
        "daily-report.initial-delay=1h"
})
class IntegratedTelegramModeTest {
    @Autowired ApplicationContext context;

    @Test void startsBackendWithoutATokenAndDoesNotCreateCommandPoller() {
        assertThat(context.getBeansOfType(TelegramCommandService.class)).isEmpty();
        assertThat(context.getBean(IntegrationSpeedController.class)).isNotNull();
        assertThat(context.getBean(NotificationDeliveryService.class).channel()).isEqualTo("ASSISTANT");
    }
}
