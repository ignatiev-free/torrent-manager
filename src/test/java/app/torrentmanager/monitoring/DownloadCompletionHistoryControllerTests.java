package app.torrentmanager.monitoring;

import app.torrentmanager.config.DownloadHistoryProperties;
import app.torrentmanager.config.EmailNotificationProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadCompletionHistoryControllerTests {
    @Test
    void delegatesRequestedLimitToHistory() {
        InMemoryStore store = new InMemoryStore();
        DownloadCompletionHistoryService service = new DownloadCompletionHistoryService(store,
                new DownloadHistoryProperties(Path.of("unused.json"), 100),
                new EmailNotificationProperties(false, "", java.time.Duration.ofSeconds(30)));
        service.record(new DownloadCompletedEvent("first", "First", Instant.parse("2026-08-19T10:00:00Z")));
        service.record(new DownloadCompletedEvent("second", "Second", Instant.parse("2026-08-19T10:01:00Z")));

        List<DownloadCompletionRecord> response = new DownloadCompletionHistoryController(service).recent(1);

        assertThat(response).extracting(DownloadCompletionRecord::hash).containsExactly("second");
    }

    private static class InMemoryStore implements DownloadCompletionHistoryStore {
        private List<DownloadCompletionRecord> records = new ArrayList<>();

        @Override
        public List<DownloadCompletionRecord> load() {
            return List.copyOf(records);
        }

        @Override
        public void save(List<DownloadCompletionRecord> records) {
            this.records = new ArrayList<>(records);
        }
    }
}
