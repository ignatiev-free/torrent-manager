package app.torrentmanager.monitoring;

import app.torrentmanager.config.DownloadHistoryProperties;
import app.torrentmanager.config.EmailNotificationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class DownloadCompletionHistoryService {
    private final DownloadCompletionHistoryStore store;
    private final int maxEntries;
    private final boolean notificationsEnabled;
    private final List<DownloadCompletionRecord> records;

    public DownloadCompletionHistoryService(DownloadCompletionHistoryStore store,
                                            DownloadHistoryProperties properties,
                                            EmailNotificationProperties emailProperties) {
        this.store = store;
        this.maxEntries = properties.maxEntries();
        this.notificationsEnabled = emailProperties.enabled();
        this.records = new ArrayList<>(store.load());
        trimToLimit();
    }

    @EventListener
    public synchronized void record(DownloadCompletedEvent event) {
        records.add(DownloadCompletionRecord.from(event, notificationsEnabled));
        trimToLimit();
        store.save(records);
    }

    public synchronized Optional<DownloadCompletionRecord> nextPending(Instant now) {
        return records.stream()
                .filter(record -> record.deliveryStatus() == DeliveryStatus.PENDING)
                .filter(record -> record.nextAttemptAt() == null || !record.nextAttemptAt().isAfter(now))
                .findFirst();
    }

    public synchronized void replace(UUID id, DownloadCompletionRecord replacement) {
        for (int index = 0; index < records.size(); index++) {
            if (records.get(index).id().equals(id)) {
                records.set(index, replacement);
                store.save(records);
                return;
            }
        }
        throw new IllegalArgumentException("Запись истории загрузок не найдена: " + id);
    }

    public synchronized List<DownloadCompletionRecord> recent(int limit) {
        int actualLimit = Math.min(Math.max(limit, 1), maxEntries);
        List<DownloadCompletionRecord> result = new ArrayList<>(records);
        Collections.reverse(result);
        return List.copyOf(result.subList(0, Math.min(actualLimit, result.size())));
    }

    private void trimToLimit() {
        if (records.size() > maxEntries) {
            records.subList(0, records.size() - maxEntries).clear();
        }
    }
}
