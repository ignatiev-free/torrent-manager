package app.torrentmanager.notification;

import app.torrentmanager.monitoring.DownloadCompletionHistoryService;
import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.TransferInfo;
import app.torrentmanager.report.DailyReportService;
import app.torrentmanager.report.StorageCapacityService;
import app.torrentmanager.settings.RuntimeSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelegramCommandServiceTests {
    private final TelegramClient telegram = mock(TelegramClient.class);
    private final QBitClient qbit = mock(QBitClient.class);
    private final DailyReportService reports = mock(DailyReportService.class);
    private final RuntimeSettingsService settings = mock(RuntimeSettingsService.class);
    private final Map<String, String> config = Map.of(
            "telegram-notifications.enabled", "true",
            "telegram-notifications.commands-enabled", "true",
            "telegram-notifications.bot-token", "123:test",
            "telegram-notifications.chat-id", "42");

    @TempDir
    Path directory;
    private TelegramCommandService commands;

    @BeforeEach
    void setup() {
        when(settings.valuesSnapshot()).thenReturn(config);
        commands = new TelegramCommandService(telegram, settings, qbit, reports,
                mock(DownloadCompletionHistoryService.class), mock(StorageCapacityService.class),
                mock(app.torrentmanager.analytics.TorrentAnalyticsService.class),
                app.torrentmanager.manager.ManagerStateStore.noOp(), directory.resolve("offset"));
    }

    @Test
    void rejectsSpeedButtonFromAnotherUserOrGroup() {
        handleButton(99, 42, "private");
        handleButton(42, 42, "group");
        handleButton(42, 99, "private");
        verifyNoInteractions(qbit, telegram);
    }

    @Test
    void updatesOnlyDownloadLimitInCurrentModeAndShowsReadback() {
        long limit = 8000L * 1024;
        when(qbit.alternativeSpeedMode()).thenReturn(true);
        when(qbit.getTransferInfo()).thenReturn(new TransferInfo(1024, limit, true));

        handleButton(42, 42, "private");

        verify(qbit).setDownloadLimit(limit, true);
        verify(telegram).acknowledge(config, "press");
        verify(telegram).send(eq(config), contains("8 000 КиБ/с"), any(), any());
    }

    @Test
    void reportUsesExistingManualReportService() {
        var update = JsonMapper.builder().build().readTree("""
                {"message":{"from":{"id":42},"chat":{"id":42,"type":"private"},"text":"/report"}}
                """);
        commands.handle(config, update);
        verify(reports).sendManual();
    }

    @Test
    void refreshEditsTheSameReportMessage() {
        when(qbit.getTorrents()).thenReturn(java.util.List.of());
        var update = JsonMapper.builder().build().readTree("""
                {"callback_query":{"id":"press","data":"current:refresh","from":{"id":42},
                "message":{"message_id":77,"chat":{"id":42,"type":"private"}}}}
                """);

        commands.handle(config, update);

        verify(telegram).edit(eq(config), contains("Активных загрузок нет"), any(), any(), eq(77L));
    }

    @Test
    void restoresExpiredLimitFromDisk() throws java.io.IOException {
        long original = 3000L * 1024;
        var timer = directory.resolve("telegram-speed-timer");
        java.nio.file.Files.writeString(timer, original + " 0 false 2000-01-01T00:00:00Z");
        when(qbit.getTransferInfo()).thenReturn(new TransferInfo(0, 0, false),
                new TransferInfo(0, original, false));

        commands.restoreSpeed();

        verify(qbit).setDownloadLimit(original, false);
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(timer));
    }

    @Test
    void cancelsRestoreIfLimitChangedManually() throws java.io.IOException {
        var timer = directory.resolve("telegram-speed-timer");
        java.nio.file.Files.writeString(timer, "3072000 0 false 2000-01-01T00:00:00Z");
        when(qbit.getTransferInfo()).thenReturn(new TransferInfo(0, 8192000, false));

        commands.restoreSpeed();

        verify(qbit, org.mockito.Mockito.never()).setDownloadLimit(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean());
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(timer));
    }

    private void handleButton(long sender, long chat, String type) {
        var update = JsonMapper.builder().build().readTree("""
                {"callback_query":{"id":"press","data":"speed:work:manual","from":{"id":%d},
                "message":{"chat":{"id":%d,"type":"%s"}}}}
                """.formatted(sender, chat, type));
        commands.handle(config, update);
    }
}
