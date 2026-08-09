package app.torrentmanager.manager;

import app.torrentmanager.config.ManagerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileManagerStateStoreTests {
    @TempDir
    Path temporaryDirectory;

    @Test
    void restoresPausedTorrentsAfterRestart() {
        Path stateFile = temporaryDirectory.resolve("state.properties");
        var properties = new ManagerProperties(true, Duration.ofMinutes(1), 5, 1,
                Duration.ofMinutes(5), DataSize.ofKilobytes(400),
                Duration.ofMinutes(15), 0.40, DataSize.ofKilobytes(50), 0.80,
                Duration.ofMinutes(5), Duration.ofMinutes(30),
                Duration.ofMinutes(3), stateFile);
        Instant retryAt = Instant.parse("2026-08-06T01:30:00Z");

        new FileManagerStateStore(properties).save(Map.of("torrent-hash", retryAt));
        Map<String, Instant> restored = new FileManagerStateStore(properties).load();

        assertThat(restored).containsExactlyEntriesOf(Map.of("torrent-hash", retryAt));
    }
}
