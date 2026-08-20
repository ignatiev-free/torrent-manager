package app.torrentmanager.monitoring;

import app.torrentmanager.config.DownloadMonitorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileDownloadMonitorStateStoreTests {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsSnapshotAcrossRestart() {
        Path stateFile = temporaryDirectory.resolve("download-monitor.properties");
        FileDownloadMonitorStateStore store = new FileDownloadMonitorStateStore(
                new DownloadMonitorProperties(stateFile));
        DownloadMonitorState state = new DownloadMonitorState(
                Instant.parse("2026-08-19T10:00:00Z"),
                Map.of(
                        "incomplete-hash", new MonitoredTorrent("Фильм 1", false),
                        "complete-hash", new MonitoredTorrent("Film 2", true)));

        store.save(state);

        assertThat(new FileDownloadMonitorStateStore(new DownloadMonitorProperties(stateFile)).load())
                .contains(state);
    }
}
