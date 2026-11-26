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

    @Test
    void integratedModeKeepsEventsAcrossRestartAndOnlyAssistantAcknowledges() throws Exception {
        var mode = new TelegramMode(TelegramMode.Mode.INTEGRATED);
        var json = JsonMapper.builder().build();
        var clock = Clock.fixed(START, ZoneOffset.UTC);
        var first = new NotificationDeliveryService(settings, telegram, email, json, queue, clock, mode);
        assertThat(first.submit(MESSAGE)).isTrue();
        first.deliverPending();
        var restarted = new NotificationDeliveryService(settings, telegram, email, json, queue, clock, mode);
        var event = restarted.pendingForAssistant().getFirst();
        assertThat(event.text()).isEqualTo(MESSAGE.telegramText());
        assertThat(first.pendingForAssistant().getFirst().id()).isEqualTo(event.id());
        assertThat(restarted.claimAssistant(event.id())).isEqualTo(START.plusSeconds(285));
        assertThat(first.claimAssistant(event.id())).isNull();
        assertThat(restarted.acknowledgeAssistant(event.id())).isTrue();
        assertThat(restarted.acknowledgeAssistant(event.id())).isTrue();
        assertThat(first.pendingForAssistant()).isEmpty();
        verifyNoInteractions(telegram, email);
    }

    @Test
    void integratedModeFallsBackOnceAfterFiveMinutesEvenAfterUnacknowledgedClaim() throws Exception {
        var mode = new TelegramMode(TelegramMode.Mode.INTEGRATED);
        var json = JsonMapper.builder().build();
        var first = new NotificationDeliveryService(settings, telegram, email, json,
                queue, Clock.fixed(START, ZoneOffset.UTC), mode);
        assertThat(first.submit(MESSAGE)).isTrue();
        var event = first.pendingForAssistant().getFirst();
        assertThat(first.claimAssistant(event.id())).isNotNull();
        assertThat(first.pendingForAssistant()).isEmpty();
        var beforeDeadline = new NotificationDeliveryService(settings, telegram, email, json,
                queue, Clock.fixed(START.plusSeconds(299), ZoneOffset.UTC), mode);
        beforeDeadline.deliverPending();
        verifyNoInteractions(telegram, email);
        var expired = new NotificationDeliveryService(settings, telegram, email, json,
                queue, Clock.fixed(START.plusSeconds(300), ZoneOffset.UTC), mode);
        assertThat(expired.claimAssistant(event.id())).isNull();
        expired.deliverPending();
        expired.deliverPending();
        verify(email).send(MESSAGE);
        verifyNoInteractions(telegram);
    }

    @Test
    void integratedModeDoesNotSendEmailWhenOutboxWriteFails() throws Exception {
        var blocked = java.nio.file.Files.writeString(queue.resolve("blocked"), "not a directory");
        var worker = new NotificationDeliveryService(settings, telegram, email, JsonMapper.builder().build(),
                blocked, Clock.fixed(START, ZoneOffset.UTC), new TelegramMode(TelegramMode.Mode.INTEGRATED));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> worker.submit(MESSAGE));
        verifyNoInteractions(email, telegram);
    }

    @Test
    void directTelegramHttpIsBlockedInIntegratedMode() {
        var mode = new TelegramMode(TelegramMode.Mode.INTEGRATED);
        var client = new TelegramClient(settings, JsonMapper.builder().build(), mode);
        org.junit.jupiter.api.Assertions.assertThrows(TelegramClient.TelegramDeliveryException.class,
                () -> client.updates(java.util.Map.of(), 0));
    }
    private NotificationDeliveryService workerAt(long seconds) {
        return new NotificationDeliveryService(settings, telegram, email, JsonMapper.builder().build(),
                queue, Clock.fixed(START.plusSeconds(seconds), ZoneOffset.UTC));
    }
}
