package app.torrentmanager.report;

import app.torrentmanager.config.StorageReportProperties;
import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.Torrent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageCapacityServiceTests {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void groupsEveryIncompleteTorrentBySavePathAndCalculatesProjectedSpace() throws IOException {
        QBitClient qbit = mock(QBitClient.class);
        FileSpaceProbe probe = mock(FileSpaceProbe.class);
        StorageReportProperties properties = properties(true);
        when(qbit.getTorrents()).thenReturn(List.of(
                torrent("active", "downloading", 0.5, 100 * GIB, 50 * GIB,
                        "/hdd1/downloads1/incomplete"),
                torrent("stopped", "stoppedDL", 0.2, 200 * GIB, 160 * GIB,
                        "/hdd2/downloads2/incomplete/show"),
                torrent("complete", "uploading", 1.0, 300 * GIB, 0,
                        "/hdd1/downloads1/complete"),
                torrent("unknown", "metaDL", 0, 0, 0,
                        "/hdd1/downloads1/incomplete"),
                torrent("other", "queuedDL", 0.1, 10 * GIB, 9 * GIB,
                        "/other/path"),
                torrent("media", "stoppedDL", 0.9, 20 * GIB, 2 * GIB,
                        "/media2/Films")));
        when(probe.read(Path.of("/storage/hdd1")))
                .thenReturn(new FileSpaceProbe.Space(1000 * GIB, 300 * GIB));
        when(probe.read(Path.of("/storage/hdd2")))
                .thenReturn(new FileSpaceProbe.Space(2000 * GIB, 100 * GIB));

        StorageCapacitySnapshot snapshot = new StorageCapacityService(qbit, properties, probe)
                .snapshot();

        assertThat(snapshot.disks()).hasSize(2);
        assertThat(snapshot.disks().get(0)).satisfies(disk -> {
            assertThat(disk.incompleteTorrents()).isEqualTo(2);
            assertThat(disk.remainingBytes()).isEqualTo(50 * GIB);
            assertThat(disk.unknownSizeTorrents()).isEqualTo(1);
            assertThat(disk.projectedFreeBytes()).isEqualTo(250 * GIB);
        });
        assertThat(snapshot.disks().get(1).projectedFreeBytes()).isEqualTo(-62 * GIB);
        assertThat(snapshot.unmatchedTorrents()).isEqualTo(1);
        assertThat(snapshot.unmatchedRemainingBytes()).isEqualTo(9 * GIB);
    }

    @Test
    void returnsDisabledSnapshotWithoutCallingQbit() {
        StorageCapacitySnapshot snapshot = new StorageCapacityService(mock(QBitClient.class),
                properties(false), mock(FileSpaceProbe.class)).snapshot();

        assertThat(snapshot.enabled()).isFalse();
    }

    private StorageReportProperties properties(boolean enabled) {
        return new StorageReportProperties(enabled, List.of(
                new StorageReportProperties.Disk("HDD1", Path.of("/storage/hdd1"),
                        List.of("/hdd1/downloads1", "/media1")),
                new StorageReportProperties.Disk("HDD2", Path.of("/storage/hdd2"),
                        List.of("/hdd2/downloads2", "/media2"))));
    }

    private Torrent torrent(String name, String state, double progress, long size,
                            long amountLeft, String savePath) {
        return new Torrent(name, name, state, progress, 0, 0, 0, 1,
                0, size, amountLeft, savePath);
    }
}
