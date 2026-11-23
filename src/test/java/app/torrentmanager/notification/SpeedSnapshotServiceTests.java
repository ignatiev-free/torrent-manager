package app.torrentmanager.notification;

import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.TransferInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static app.torrentmanager.notification.SpeedSnapshotService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeedSnapshotServiceTests {
    @TempDir Path directory;

    @Test
    void preservesBaselineAcrossProfilesAndRejectsRevisionAfterLegacyChange() throws Exception {
        var limit = new AtomicLong(2048);
        var client = mock(QBitClient.class);
        when(client.getTransferInfo()).thenAnswer(ignored -> new TransferInfo(0, limit.get(), true));
        when(client.alternativeSpeedMode()).thenReturn(true);
        doAnswer(call -> { limit.set(call.getArgument(0)); return null; })
                .when(client).setDownloadLimit(anyLong(), eq(true));

        var service = new SpeedSnapshotService(client, directory.resolve("offset"));
        var before = Instant.now();
        var hour = service.change(new Change(Profile.WORK, Period.HOUR, service.read().revision()));
        assertTrue(hour.alternativeMode());
        assertEquals(8192000, hour.downloadLimitBps());
        assertFalse(hour.restoreAt().isBefore(before.plus(Duration.ofHours(1))));
        assertFalse(hour.restoreAt().isAfter(Instant.now().plus(Duration.ofHours(1))));

        var morning = service.change(new Change(Profile.MIN, Period.MORNING, hour.revision()));
        assertEquals(3072000, morning.downloadLimitBps());
        var zone = ZoneId.systemDefault();
        assertEquals(8, morning.restoreAt().atZone(zone).getHour());
        assertEquals(0, morning.restoreAt().atZone(zone).getMinute());
        assertTrue(morning.restoreAt().isAfter(before));
        assertEquals(2048, service.timer().previous());

        // The old bot and HTTP are wired to this same service in production.
        service.applyLegacy(Profile.MAX.bytesPerSecond, "manual");
        assertThrows(StaleSpeed.class, () -> service.change(new Change(Profile.WORK, Period.HOUR, morning.revision())));
        assertEquals(0, service.read().downloadLimitBps());
        assertFalse(Files.exists(directory.resolve("telegram-speed-timer")));
        var manual = service.change(new Change(Profile.MAX, Period.MANUAL, service.read().revision()));
        assertNull(manual.restoreAt());
        verify(client, times(2)).setDownloadLimit(0, true);
    }

    @Test
    void retainsActiveTimerWhenQbitRejectsAnotherLimit() throws Exception {
        Path timer = directory.resolve("telegram-speed-timer");
        String original = "1024 3072000 false 2030-01-01T08:00:00Z";
        Files.writeString(timer, original);
        var client = mock(QBitClient.class);
        when(client.getTransferInfo()).thenReturn(new TransferInfo(0, 3072000, false));
        doThrow(new IllegalStateException("rejected")).when(client).setDownloadLimit(8192000, false);
        var service = new SpeedSnapshotService(client, directory.resolve("offset"));
        assertThrows(IllegalStateException.class,
                () -> service.change(new Change(Profile.WORK, Period.HOUR, service.read().revision())));
        assertEquals(original, Files.readString(timer));
        assertFalse(Files.exists(directory.resolve("telegram-speed-transition")));
    }

    @Test
    void restartedServiceReconcilesLostReplyAndRestoresOriginalLimit() throws Exception {
        Path cursor = directory.resolve("offset");
        Files.writeString(directory.resolve("telegram-speed-timer"), "1024 3072000 false 2000-01-01T07:00:00Z");
        Files.writeString(directory.resolve("telegram-speed-transition"), "1024 8192000 false 2000-01-01T08:00:00Z");
        var client = mock(QBitClient.class);
        when(client.getTransferInfo()).thenReturn(new TransferInfo(0, 8192000, false),
                new TransferInfo(0, 8192000, false), new TransferInfo(0, 1024, false));
        new SpeedSnapshotService(client, cursor).restoreSpeed();
        verify(client).setDownloadLimit(1024, false);
        assertFalse(Files.exists(directory.resolve("telegram-speed-transition")));
        assertFalse(Files.exists(directory.resolve("telegram-speed-timer")));
    }}
