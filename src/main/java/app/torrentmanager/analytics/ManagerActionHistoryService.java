package app.torrentmanager.analytics;

import app.torrentmanager.config.DailyReportProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ManagerActionHistoryService {
    private final ManagerActionHistoryStore store;
    private final int maxEntries;
    private final List<ManagerActionEvent> events;

    public ManagerActionHistoryService(ManagerActionHistoryStore store,
                                       DailyReportProperties properties) {
        this.store = store;
        this.maxEntries = properties.maxActionHistoryEntries();
        this.events = new ArrayList<>(store.load());
        trim();
    }

    @EventListener
    public synchronized void record(ManagerActionEvent event) {
        events.add(event);
        trim();
        store.save(events);
    }

    public synchronized List<ManagerActionEvent> between(Instant fromInclusive, Instant toExclusive) {
        return events.stream()
                .filter(event -> !event.at().isBefore(fromInclusive))
                .filter(event -> event.at().isBefore(toExclusive))
                .toList();
    }

    private void trim() {
        if (events.size() > maxEntries) {
            events.subList(0, events.size() - maxEntries).clear();
        }
    }
}
