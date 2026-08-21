package app.torrentmanager.monitoring;

import app.torrentmanager.config.DownloadHistoryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileDownloadCompletionHistoryStoreTests {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsHistoryAcrossRestart() {
        Path historyFile = temporaryDirectory.resolve("completions.json");
        DownloadHistoryProperties properties = new DownloadHistoryProperties(historyFile, 1000);
        var objectMapper = JsonMapper.builder().findAndAddModules().build();
        DownloadCompletionRecord record = DownloadCompletionRecord.from(
                new DownloadCompletedEvent("hash", "Фильм", Instant.parse("2026-08-19T10:00:00Z")), false);

        new FileDownloadCompletionHistoryStore(objectMapper, properties).save(List.of(record));

        assertThat(new FileDownloadCompletionHistoryStore(objectMapper, properties).load())
                .containsExactly(record);
    }
}
