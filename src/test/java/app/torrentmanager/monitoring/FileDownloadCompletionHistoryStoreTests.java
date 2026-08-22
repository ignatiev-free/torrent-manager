package app.torrentmanager.monitoring;

import app.torrentmanager.config.DownloadHistoryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
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

    @Test
    void loadsHistoryWrittenBeforeNotificationFieldsWereAdded() throws Exception {
        Path historyFile = temporaryDirectory.resolve("legacy-completions.json");
        Files.writeString(historyFile, """
                [
                  {
                    "id": "802010e0-885f-46fa-a4c4-ec94bdd2d596",
                    "hash": "legacy-hash",
                    "name": "Legacy movie",
                    "completedAt": "2026-08-19T10:00:00Z",
                    "deliveryStatus": "NOT_CONFIGURED"
                  }
                ]
                """);
        DownloadHistoryProperties properties = new DownloadHistoryProperties(historyFile, 1000);
        var objectMapper = JsonMapper.builder().findAndAddModules().build();

        DownloadCompletionRecord record = new FileDownloadCompletionHistoryStore(objectMapper, properties)
                .load().getFirst();

        assertThat(record.hash()).isEqualTo("legacy-hash");
        assertThat(record.deliveryStatus()).isEqualTo(DeliveryStatus.NOT_CONFIGURED);
        assertThat(record.attempts()).isZero();
    }
}
