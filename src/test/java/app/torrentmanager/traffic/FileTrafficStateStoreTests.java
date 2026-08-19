package app.torrentmanager.traffic;

import app.torrentmanager.config.TrafficStatsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FileTrafficStateStoreTests {
    @TempDir
    Path temporaryDirectory;

    @Test
    void restoresTrafficCountersAfterRestart() {
        Path stateFile = temporaryDirectory.resolve("traffic.properties");
        TrafficState expected = new TrafficState(
                LocalDate.parse("2026-08-19"),
                12_000, 3_000, 2_000, 500,
                Instant.parse("2026-08-19T06:00:00Z"));
        FileTrafficStateStore store = new FileTrafficStateStore(new TrafficStatsProperties(stateFile));

        store.save(expected);

        assertThat(new FileTrafficStateStore(new TrafficStatsProperties(stateFile)).load())
                .contains(expected);
    }
}
