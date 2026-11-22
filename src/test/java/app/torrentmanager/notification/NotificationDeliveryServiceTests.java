package app.torrentmanager.notification;

import app.torrentmanager.settings.RuntimeSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationDeliveryServiceTests {
    private static final Instant START = Instant.parse("2026-11-11T12:00:00Z");
    private static final NotificationMessage MESSAGE =
            new NotificationMessage("Загрузка завершена", "Полное письмо", "<p>Письмо</p>", "Коротко");

    @TempDir
    Path queue;
    private final RuntimeSettingsService settings = mock(RuntimeSettingsService.class);
    private final TelegramClient telegram = mock(TelegramClient.class);
    private final EmailNotificationSender email = mock(EmailNotificationSender.class);

    @BeforeEach
    void configure() {
        when(settings.notificationsAllowed()).thenReturn(true);
        when(settings.value("telegram-notifications.enabled")).thenReturn("true");
        when(settings.value("control.notifications-suppressed-before")).thenReturn("");
    }

    @Test
    void confirmedTelegramDeliveryClearsTheQueue() {
        var delivery = workerAt(0);
        assertThat(delivery.submit(MESSAGE)).isTrue();
        delivery.deliverPending();
        workerAt(300).deliverPending();

        verify(telegram).send(MESSAGE.telegramText(), Duration.ofSeconds(10));
        verifyNoInteractions(email);
    }

    @Test
    void retriesTelegramThenFallsBackAfterFiveMinutesAcrossRestarts() {
        doThrow(new TelegramClient.TelegramDeliveryException("timeout")).when(telegram).send(any(), any());
        assertThat(workerAt(0).submit(MESSAGE)).isTrue();
        workerAt(0).deliverPending();
        workerAt(30).deliverPending();
        workerAt(299).deliverPending();
        verifyNoInteractions(email);
        verify(telegram).send(MESSAGE.telegramText(), Duration.ofSeconds(1));

        workerAt(300).deliverPending();
        workerAt(600).deliverPending();
        verify(email).send(MESSAGE);
        verify(telegram, times(3)).send(any(), any());
    }

    @Test
    void suppressionCutoffDiscardsQueuedMessageEvenAfterResuming() {
        workerAt(0).submit(MESSAGE);
        when(settings.value("control.notifications-suppressed-before")).thenReturn(START.plusSeconds(1).toString());
        workerAt(300).deliverPending();

        verifyNoInteractions(telegram, email);
        when(settings.value("control.notifications-suppressed-before")).thenReturn("");
        workerAt(301).deliverPending();
        verifyNoInteractions(telegram, email);
    }

    @Test
    void sendsEmailImmediatelyWhenTelegramIsDisabled() {
        when(settings.value("telegram-notifications.enabled")).thenReturn("false");
        assertThat(workerAt(0).submit(MESSAGE)).isFalse();
        verify(email).send(MESSAGE);
        verifyNoInteractions(telegram);
    }

    private NotificationDeliveryService workerAt(long seconds) {
        return new NotificationDeliveryService(settings, telegram, email, JsonMapper.builder().build(),
                queue, Clock.fixed(START.plusSeconds(seconds), ZoneOffset.UTC));
    }
}
