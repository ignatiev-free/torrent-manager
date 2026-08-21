package app.torrentmanager.notification;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailNotificationTestControllerTests {
    @Test
    void sendsTestMessageThroughConfiguredSender() {
        NotificationSender sender = mock(NotificationSender.class);
        when(sender.channel()).thenReturn("EMAIL");
        EmailNotificationTestController controller = new EmailNotificationTestController(sender);

        Map<String, String> response = controller.sendTestNotification();

        ArgumentCaptor<NotificationMessage> message =
                ArgumentCaptor.forClass(NotificationMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().subject()).isEqualTo("Тестовое уведомление Torrent Manager");
        assertThat(response).containsEntry("status", "sent").containsEntry("channel", "EMAIL");
    }
}
