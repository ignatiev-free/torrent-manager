package app.torrentmanager.monitoring;

import app.torrentmanager.config.DownloadHistoryProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class DownloadCompletionHistoryService {
    private final DownloadCompletionHistoryStore store;
    private final int maxEntries;
    private final List<DownloadCompletionRecord> records;

    public DownloadCompletionHistoryService(DownloadCompletionHistoryStore store,
                                            DownloadHistoryProperties properties) {
        this.store = store;
        this.maxEntries = properties.maxEntries();
        this.records = new ArrayList<>(store.load());
        trimToLimit();
    }

    @EventListener
    public synchronized void record(DownloadCompletedEvent event) {
        records.add(DownloadCompletionRecord.from(event));
        trimToLimit();
        store.save(records);
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
