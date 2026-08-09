package app.torrentmanager.manager;

import app.torrentmanager.config.ManagerProperties;
import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.Torrent;
import app.torrentmanager.qbit.TransferInfo;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class TorrentManagerServiceTests {
    private static final Instant START = Instant.parse("2026-08-05T12:00:00Z");

    @Test
    void stopsTorrentAfterFiveMinutesBelowThreshold() {
        QBitClient client = mock(QBitClient.class);
        Torrent slow = torrent("slow", 100 * 1024, 3);
        when(client.getTorrents()).thenReturn(List.of(slow));
        TorrentManagerService manager = manager(client, false);

        for (int minute = 0; minute <= 5; minute++) {
            manager.inspectAt(START.plus(Duration.ofMinutes(minute)).plusMillis(minute * 20L));
        }

        verify(client).stop("slow");
    }

    @Test
    void keepsTorrentWhoseAverageSpeedIsHighEnough() {
        QBitClient client = mock(QBitClient.class);
        when(client.getTorrents())
                .thenReturn(List.of(torrent("fast", 100 * 1024, 3)))
                .thenReturn(List.of(torrent("fast", 800 * 1024, 3)));
        TorrentManagerService manager = manager(client, false);

        manager.inspectAt(START);
        manager.inspectAt(START.plus(Duration.ofMinutes(5)));

        verify(client, never()).stop("fast");
    }

    @Test
    void dryRunNeverChangesQbittorrent() {
        QBitClient client = mock(QBitClient.class);
        when(client.getTorrents()).thenReturn(List.of(torrent("slow", 0, 0)));
        TorrentManagerService manager = manager(client, true);

        manager.inspectAt(START);
        manager.inspectAt(START.plus(Duration.ofMinutes(5)));

        verify(client, never()).stop("slow");
        verify(client, never()).start("slow");
    }

    @Test
    void oneMinuteWindowWorksDespiteSchedulerDrift() {
        QBitClient client = mock(QBitClient.class);
        when(client.getTorrents()).thenReturn(List.of(torrent("slow", 100 * 1024, 3)));
        TorrentManagerService manager = manager(client, false, Duration.ofMinutes(1));

        manager.inspectAt(START);
        manager.inspectAt(START.plus(Duration.ofMinutes(1)).plusMillis(50));

        verify(client).stop("slow");
    }

    @Test
    void neverStopsManuallyForcedTorrent() {
        QBitClient client = mock(QBitClient.class);
        Torrent forced = new Torrent("forced", "forced", "forcedDL", 0.5, 0, 0, 0, 1);
        when(client.getTorrents()).thenReturn(List.of(forced));
        TorrentManagerService manager = manager(client, false, Duration.ofMinutes(1));

        manager.inspectAt(START);
        manager.inspectAt(START.plus(Duration.ofMinutes(1)).plusMillis(50));

        verify(client, never()).stop("forced");
    }

    @Test
    void keepsFiveFastestTorrentsWhenActiveLimitIsExceeded() {
        QBitClient client = mock(QBitClient.class);
        List<Torrent> torrents = List.of(
                torrent("one", 900 * 1024, 3), torrent("two", 800 * 1024, 3),
                torrent("three", 700 * 1024, 3), torrent("four", 600 * 1024, 3),
                torrent("five", 500 * 1024, 3), torrent("six", 450 * 1024, 3),
                torrent("seven", 425 * 1024, 3), torrent("eight", 410 * 1024, 3));
        when(client.getTorrents()).thenReturn(torrents);
        TorrentManagerService manager = manager(client, false);

        manager.inspectAt(START);
        manager.inspectAt(START.plus(Duration.ofMinutes(5)));

        verify(client).stop("six");
        verify(client).stop("seven");
        verify(client).stop("eight");
        verify(client, times(3)).stop(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void doesNotRetryPausedTorrentInSameCycleAsStoppingAnotherTorrent() {
        QBitClient client = mock(QBitClient.class);
        ManagerStateStore stateStore = stateStoreWithEligible("paused");
        List<Torrent> torrents = List.of(
                torrent("slow", 100 * 1024, 2),
                torrent("fast-1", 800 * 1024, 2), torrent("fast-2", 800 * 1024, 2),
                torrent("fast-3", 800 * 1024, 2), torrent("fast-4", 800 * 1024, 2),
                stoppedTorrent("paused"));
        when(client.getTorrents()).thenReturn(torrents);
        TorrentManagerService manager = manager(client, false, Duration.ofMinutes(1), stateStore);

        manager.inspectAt(START);
        manager.inspectAt(START.plus(Duration.ofMinutes(1)).plusMillis(50));

        verify(client).stop("slow");
        verify(client, never()).start("paused");
    }

    @Test
    void givesQueuedDownloadPriorityOverPausedTorrent() {
        QBitClient client = mock(QBitClient.class);
        ManagerStateStore stateStore = stateStoreWithEligible("paused");
        List<Torrent> torrents = List.of(
                torrent("fast-1", 800 * 1024, 2), torrent("fast-2", 800 * 1024, 2),
                torrent("fast-3", 800 * 1024, 2), torrent("fast-4", 800 * 1024, 2),
                queuedTorrent("new-queued"), stoppedTorrent("paused"));
        when(client.getTorrents()).thenReturn(torrents);
        TorrentManagerService manager = manager(client, false, Duration.ofMinutes(1), stateStore);

        manager.inspectAt(START);

        verify(client, never()).start("paused");
    }

    @Test
    void retriesPausedTorrentWhenSlotIsFreeAndQueueIsEmpty() {
        QBitClient client = mock(QBitClient.class);
        ManagerStateStore stateStore = stateStoreWithEligible("paused");
        List<Torrent> torrents = List.of(
                torrent("fast-1", 800 * 1024, 2), torrent("fast-2", 800 * 1024, 2),
                torrent("fast-3", 800 * 1024, 2), torrent("fast-4", 800 * 1024, 2),
                stoppedTorrent("paused"));
        when(client.getTorrents()).thenReturn(torrents);
        TorrentManagerService manager = manager(client, false, Duration.ofMinutes(1), stateStore);

        manager.inspectAt(START);

        verify(client).start("paused");
    }

    @Test
    void usesLowerThresholdAndLongerWindowWhenDownloadIsLimited() {
        QBitClient client = mock(QBitClient.class);
        when(client.getTorrents()).thenReturn(List.of(torrent("acceptable", 200 * 1024, 2)));
        TorrentManagerService manager = manager(client, false);
        when(client.getTransferInfo()).thenReturn(limited(500 * 1024, 2048 * 1024));

        manager.inspectAt(START);
        manager.inspectAt(START.plus(Duration.ofMinutes(15)));

        verify(client, never()).stop("acceptable");
    }

    @Test
    void doesNotRotateSlowTorrentWhenLimitedChannelIsSaturated() {
        QBitClient client = mock(QBitClient.class);
        when(client.getTorrents()).thenReturn(List.of(torrent("slow", 100 * 1024, 2)));
        TorrentManagerService manager = manager(client, false);
        when(client.getTransferInfo()).thenReturn(limited(1800 * 1024, 2048 * 1024));

        manager.inspectAt(START);
        manager.inspectAt(START.plus(Duration.ofMinutes(15)));

        verify(client, never()).stop("slow");
    }

    @Test
    void resetsObservationWhenRateLimitChanges() {
        QBitClient client = mock(QBitClient.class);
        when(client.getTorrents()).thenReturn(List.of(torrent("slow", 100 * 1024, 2)));
        TorrentManagerService manager = manager(client, false);
        when(client.getTransferInfo())
                .thenReturn(unlimited())
                .thenReturn(limited(100 * 1024, 2048 * 1024))
                .thenReturn(limited(100 * 1024, 2048 * 1024));

        manager.inspectAt(START);
        manager.inspectAt(START.plus(Duration.ofMinutes(5)));
        manager.inspectAt(START.plus(Duration.ofMinutes(19)));

        verify(client, never()).stop("slow");
    }

    private TorrentManagerService manager(QBitClient client, boolean dryRun) {
        return manager(client, dryRun, Duration.ofMinutes(5));
    }

    private TorrentManagerService manager(QBitClient client, boolean dryRun, Duration slowWindow) {
        return manager(client, dryRun, slowWindow, ManagerStateStore.noOp());
    }

    private TorrentManagerService manager(QBitClient client, boolean dryRun, Duration slowWindow,
                                          ManagerStateStore stateStore) {
        when(client.getTransferInfo()).thenReturn(unlimited());
        var properties = new ManagerProperties(dryRun, Duration.ofMinutes(1), 5, 10,
                slowWindow, DataSize.ofKilobytes(400), Duration.ofMinutes(15), 0.40,
                DataSize.ofKilobytes(50), 0.80, Duration.ofMinutes(5),
                Duration.ofMinutes(30), Duration.ofMinutes(3),
                Path.of("build/test-state.properties"));
        return new TorrentManagerService(client, properties, Clock.fixed(START, ZoneOffset.UTC), stateStore);
    }

    private TransferInfo unlimited() {
        return new TransferInfo(0, 0, false);
    }

    private TransferInfo limited(long speed, long limit) {
        return new TransferInfo(speed, limit, true);
    }

    private Torrent torrent(String hash, long speed, int seeds) {
        return new Torrent(hash, hash, "downloading", 0.5, speed, seeds, seeds, 1);
    }

    private Torrent stoppedTorrent(String hash) {
        return new Torrent(hash, hash, "stoppedDL", 0.5, 0, 0, 0, 1);
    }

    private Torrent queuedTorrent(String hash) {
        return new Torrent(hash, hash, "queuedDL", 0.0, 0, 0, 0, 1);
    }

    private ManagerStateStore stateStoreWithEligible(String hash) {
        ManagerStateStore stateStore = mock(ManagerStateStore.class);
        when(stateStore.load()).thenReturn(Map.of(hash, START.minus(Duration.ofMinutes(1))));
        return stateStore;
    }
}
