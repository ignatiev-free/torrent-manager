package app.torrentmanager.monitoring;

import app.torrentmanager.config.DownloadHistoryProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadCompletionHistoryServiceTests {
    @Test
    void storesEventsAndReturnsNewestFirst() {
        InMemoryStore store = new InMemoryStore();
        DownloadCompletionHistoryService service = service(store, 10);

        service.record(event("first", "2026-08-19T10:00:00Z"));
        service.record(event("second", "2026-08-19T10:01:00Z"));

        assertThat(service.recent(10)).extracting(DownloadCompletionRecord::hash)
                .containsExactly("second", "first");
        assertThat(store.records).hasSize(2);
        assertThat(store.records).allMatch(record -> record.deliveryStatus() == DeliveryStatus.NOT_CONFIGURED);
    }

    @Test
    void keepsOnlyConfiguredNumberOfEntries() {
        InMemoryStore store = new InMemoryStore();
        DownloadCompletionHistoryService service = service(store, 2);

        service.record(event("first", "2026-08-19T10:00:00Z"));
        service.record(event("second", "2026-08-19T10:01:00Z"));
        service.record(event("third", "2026-08-19T10:02:00Z"));

        assertThat(service.recent(10)).extracting(DownloadCompletionRecord::hash)
                .containsExactly("third", "second");
        assertThat(store.records).extracting(DownloadCompletionRecord::hash)
                .containsExactly("second", "third");
    }

    @Test
    void restoresExistingHistory() {
        InMemoryStore store = new InMemoryStore();
        DownloadCompletionRecord existing = DownloadCompletionRecord.from(
                event("restored", "2026-08-19T10:00:00Z"));
        store.records = new ArrayList<>(List.of(existing));

        assertThat(service(store, 10).recent(50)).containsExactly(existing);
    }

    private DownloadCompletionHistoryService service(InMemoryStore store, int maxEntries) {
        return new DownloadCompletionHistoryService(store,
                new DownloadHistoryProperties(Path.of("unused.json"), maxEntries));
    }

    private DownloadCompletedEvent event(String hash, String at) {
        return new DownloadCompletedEvent(hash, "Name " + hash, Instant.parse(at));
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
