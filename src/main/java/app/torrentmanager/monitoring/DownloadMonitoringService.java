package app.torrentmanager.monitoring;

import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.Torrent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DownloadMonitoringService {
    private static final Logger log = LoggerFactory.getLogger(DownloadMonitoringService.class);

    private final QBitClient client;
    private final DownloadMonitorStateStore stateStore;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private DownloadMonitorState state;

    @Autowired
    public DownloadMonitoringService(QBitClient client, DownloadMonitorStateStore stateStore,
                                     ApplicationEventPublisher eventPublisher) {
        this(client, stateStore, eventPublisher, Clock.systemDefaultZone());
    }

    DownloadMonitoringService(QBitClient client, DownloadMonitorStateStore stateStore,
                              ApplicationEventPublisher eventPublisher, Clock clock) {
        this.client = client;
        this.stateStore = stateStore;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.state = stateStore.load().orElse(null);
    }

    @Scheduled(fixedDelayString = "${download-monitor.poll-interval}",
            initialDelayString = "${download-monitor.initial-delay}")
    public void inspect() {
        try {
            inspectAt(client.getTorrents(), clock.instant());
        } catch (Exception exception) {
            log.error("Ошибка мониторинга загрузок qBittorrent: {}", exception.getMessage(), exception);
        }
    }

    void inspectAt(List<Torrent> torrents, Instant now) {
        Map<String, MonitoredTorrent> current = snapshot(torrents);
        if (state == null) {
            state = new DownloadMonitorState(now, current);
            stateStore.save(state);
            log.info("Мониторинг загрузок инициализирован: {} торрентов, завершённые события начнутся "
                    + "со следующего изменения состояния", current.size());
            return;
        }

        for (Map.Entry<String, MonitoredTorrent> entry : current.entrySet()) {
            MonitoredTorrent previous = state.torrents().get(entry.getKey());
            MonitoredTorrent observed = entry.getValue();
            if (previous != null && !previous.complete() && observed.complete()) {
                eventPublisher.publishEvent(new DownloadCompletedEvent(entry.getKey(), observed.name(), now));
            }
        }

        state = new DownloadMonitorState(state.initializedAt(), current);
        stateStore.save(state);
    }

    private Map<String, MonitoredTorrent> snapshot(List<Torrent> torrents) {
        Map<String, MonitoredTorrent> result = new LinkedHashMap<>();
        torrents.forEach(torrent -> result.put(torrent.hash(),
                new MonitoredTorrent(torrent.name(), torrent.isComplete())));
        return result;
    }

    Optional<DownloadMonitorState> state() {
        return Optional.ofNullable(state);
    }
}
