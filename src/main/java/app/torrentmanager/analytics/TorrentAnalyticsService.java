package app.torrentmanager.analytics;

import app.torrentmanager.config.TorrentAnalyticsProperties;
import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.Torrent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "torrent-analytics.enabled", havingValue = "true", matchIfMissing = true)
public class TorrentAnalyticsService {
    private static final Logger log = LoggerFactory.getLogger(TorrentAnalyticsService.class);
    private final QBitClient client;
    private final TorrentAnalyticsStateStore store;
    private final TorrentAnalyticsProperties properties;
    private final Clock clock;
    private final Map<String, TorrentAnalyticsRecord> records = new LinkedHashMap<>();

    @Autowired
    public TorrentAnalyticsService(QBitClient client, TorrentAnalyticsStateStore store,
                                   TorrentAnalyticsProperties properties) {
        this(client, store, properties, Clock.systemDefaultZone());
    }

    TorrentAnalyticsService(QBitClient client, TorrentAnalyticsStateStore store,
                            TorrentAnalyticsProperties properties, Clock clock) {
        this.client = client;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        store.load().ifPresent(state -> records.putAll(state.torrents()));
    }

    @Scheduled(fixedDelayString = "${torrent-analytics.poll-interval:1m}",
            initialDelayString = "${torrent-analytics.initial-delay:5s}")
    public void inspect() {
        try {
            inspectAt(client.getTorrents(), clock.instant());
        } catch (RuntimeException exception) {
            log.warn("Не удалось обновить аналитику торрентов: {}", exception.getMessage());
        }
    }

    synchronized void inspectAt(List<Torrent> torrents, Instant now) {
        Map<String, TorrentAnalyticsRecord> current = new LinkedHashMap<>();
        Duration maximumElapsed = properties.pollInterval().multipliedBy(2);
        torrents.stream().filter(torrent -> !torrent.isComplete()).forEach(torrent -> {
            TorrentAnalyticsRecord previous = records.get(torrent.hash());
            current.put(torrent.hash(), previous == null
                    ? TorrentAnalyticsRecord.first(torrent, now)
                    : previous.observe(torrent, now, maximumElapsed));
        });
        records.clear();
        records.putAll(current);
        store.save(new TorrentAnalyticsState(now, records));
    }

    @EventListener
    public synchronized void recordManagerAction(ManagerActionEvent event) {
        TorrentAnalyticsRecord record = records.get(event.hash());
        records.put(event.hash(), record == null
                ? TorrentAnalyticsRecord.first(event)
                : record.action(event.type(), event.at()));
        store.save(new TorrentAnalyticsState(event.at(), records));
    }

    public synchronized List<TorrentAnalyticsRecord> snapshot() {
        List<TorrentAnalyticsRecord> result = new ArrayList<>(records.values());
        result.sort(Comparator.comparing(TorrentAnalyticsRecord::name,
                String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }
}
