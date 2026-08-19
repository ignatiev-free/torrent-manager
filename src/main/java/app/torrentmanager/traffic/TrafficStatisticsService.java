package app.torrentmanager.traffic;

import app.torrentmanager.qbit.LifetimeTransferInfo;
import app.torrentmanager.qbit.QBitClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

@Service
public class TrafficStatisticsService {
    private static final Logger log = LoggerFactory.getLogger(TrafficStatisticsService.class);
    private final QBitClient client;
    private final TrafficStateStore stateStore;
    private final Clock clock;
    private TrafficState state;

    @Autowired
    public TrafficStatisticsService(QBitClient client, FileTrafficStateStore stateStore) {
        this(client, stateStore, Clock.systemDefaultZone());
    }

    TrafficStatisticsService(QBitClient client, TrafficStateStore stateStore, Clock clock) {
        this.client = client;
        this.stateStore = stateStore;
        this.clock = clock;
        this.state = stateStore.load().orElse(null);
    }

    @Scheduled(
            fixedDelayString = "${traffic-stats.poll-interval:1m}",
            initialDelayString = "${traffic-stats.initial-delay:0s}")
    public void refreshScheduled() {
        try {
            refresh();
        } catch (RuntimeException exception) {
            log.warn("Не удалось обновить статистику трафика qBittorrent: {}", exception.getMessage());
        }
    }

    public synchronized void refresh() {
        LifetimeTransferInfo totals = client.getLifetimeTransferInfo();
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);

        if (state == null) {
            state = new TrafficState(today, totals.downloadedBytes(), totals.uploadedBytes(),
                    0, 0, now);
            stateStore.save(state);
            log.info("Начат учёт трафика qBittorrent");
            return;
        }

        if (!state.date().equals(today)) {
            state = new TrafficState(today, totals.downloadedBytes(), totals.uploadedBytes(),
                    0, 0, state.trackingSince());
            stateStore.save(state);
            log.info("Начат новый день учёта трафика qBittorrent: {}", today);
            return;
        }

        long downloadedDelta = positiveDelta(totals.downloadedBytes(), state.lastDownloadedBytes());
        long uploadedDelta = positiveDelta(totals.uploadedBytes(), state.lastUploadedBytes());
        state = new TrafficState(today, totals.downloadedBytes(), totals.uploadedBytes(),
                saturatedAdd(state.downloadedTodayBytes(), downloadedDelta),
                saturatedAdd(state.uploadedTodayBytes(), uploadedDelta),
                state.trackingSince());
        stateStore.save(state);
    }

    public synchronized TrafficSnapshot snapshot() {
        if (state == null) {
            refresh();
        }
        return new TrafficSnapshot(state.date(), state.downloadedTodayBytes(),
                state.uploadedTodayBytes(), state.lastDownloadedBytes(),
                state.lastUploadedBytes(), state.trackingSince());
    }

    private static long positiveDelta(long current, long previous) {
        return current >= previous ? current - previous : 0;
    }

    private static long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
