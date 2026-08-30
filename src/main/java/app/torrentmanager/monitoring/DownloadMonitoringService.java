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
import java.time.Duration;
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
        Map<String, MonitoredTorrent> current = snapshot(torrents, now);
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
                Long totalSeconds = observed.addedAt() == null ? null
                        : Math.max(0, Duration.between(observed.addedAt(), now).getSeconds());
                eventPublisher.publishEvent(new DownloadCompletedEvent(entry.getKey(), observed.name(), now,
                        totalSeconds, observed.activeSeconds()));
            }
        }

        state = new DownloadMonitorState(state.initializedAt(), current);
        stateStore.save(state);
    }

    private Map<String, MonitoredTorrent> snapshot(List<Torrent> torrents, Instant now) {
        Map<String, MonitoredTorrent> result = new LinkedHashMap<>();
        torrents.forEach(torrent -> {
            MonitoredTorrent previous = state == null ? null : state.torrents().get(torrent.hash());
            Instant addedAt = torrent.addedOn() > 0 ? Instant.ofEpochSecond(torrent.addedOn())
                    : previous != null && previous.addedAt() != null ? previous.addedAt() : now;
            Instant lastObserved = previous == null ? now : previous.lastObservedAt();
            long elapsed = lastObserved == null ? 0
                    : Math.max(0, Duration.between(lastObserved, now).getSeconds());
            elapsed = Math.min(elapsed, 2 * 60);
            long activeSeconds = previous == null ? 0 : previous.activeSeconds();
            if (previous != null && isActiveDownload(previous, torrent)) {
                activeSeconds += elapsed;
            }
            result.put(torrent.hash(), new MonitoredTorrent(torrent.name(), torrent.isComplete(),
                    addedAt, now, activeSeconds));
        });
        return result;
    }

    private boolean isActiveDownload(MonitoredTorrent previous, Torrent current) {
        return !previous.complete() && (current.isActivelyDownloading()
                || "uploading".equals(current.state()) || current.isComplete());
    }

    Optional<DownloadMonitorState> state() {
        return Optional.ofNullable(state);
    }
}
