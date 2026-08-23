package app.torrentmanager.analytics;

import app.torrentmanager.config.TorrentAnalyticsProperties;
import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.Torrent;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TorrentAnalyticsServiceTests {
    private static final Instant START = Instant.parse("2026-08-23T06:00:00Z");

    @Test
    void tracksProgressTimeAndManagerActions() {
        InMemoryStore store = new InMemoryStore();
        TorrentAnalyticsService service = new TorrentAnalyticsService(mock(QBitClient.class), store,
                properties(), Clock.fixed(START, ZoneOffset.UTC));

        service.inspectAt(List.of(torrent(0.10, 1024, 2)), START);
        service.inspectAt(List.of(torrent(0.25, 2048, 0)), START.plusSeconds(60));
        service.recordManagerAction(new ManagerActionEvent("hash", "Movie",
                ManagerActionType.FAIR_ROTATION, START.plusSeconds(61), 0.25));

        TorrentAnalyticsRecord record = service.snapshot().getFirst();
        assertThat(record.progress()).isEqualTo(0.25);
        assertThat(record.lastProgressAt()).isEqualTo(START.plusSeconds(60));
        assertThat(record.activeSeconds()).isEqualTo(60);
        assertThat(record.noSeedsSince()).isEqualTo(START.plusSeconds(60));
        assertThat(record.managerResumes()).isEqualTo(1);
        assertThat(record.fairRotations()).isEqualTo(1);
        assertThat(record.attemptsSinceProgress()).isEqualTo(1);
        assertThat(store.state).isPresent();
    }

    @Test
    void capsAccumulatedTimeAcrossLongApplicationDowntime() {
        TorrentAnalyticsService service = new TorrentAnalyticsService(mock(QBitClient.class),
                new InMemoryStore(), properties(), Clock.fixed(START, ZoneOffset.UTC));

        service.inspectAt(List.of(torrent(0.10, 1024, 1)), START);
        service.inspectAt(List.of(torrent(0.10, 1024, 1)), START.plus(Duration.ofHours(12)));

        assertThat(service.snapshot().getFirst().activeSeconds()).isEqualTo(120);
    }

    @Test
    void keepsManagerActionThatHappensBeforeFirstPoll() {
        TorrentAnalyticsService service = new TorrentAnalyticsService(mock(QBitClient.class),
                new InMemoryStore(), properties(), Clock.fixed(START, ZoneOffset.UTC));

        service.recordManagerAction(new ManagerActionEvent("hash", "Movie",
                ManagerActionType.FAIR_ROTATION, START, 0.40));
        service.inspectAt(List.of(torrent(0.40, 0, 0)), START.plusSeconds(5));

        TorrentAnalyticsRecord record = service.snapshot().getFirst();
        assertThat(record.managerResumes()).isEqualTo(1);
        assertThat(record.fairRotations()).isEqualTo(1);
        assertThat(record.attemptsSinceProgress()).isEqualTo(1);
    }

    private Torrent torrent(double progress, long speed, int seeds) {
        return new Torrent("hash", "Movie", "downloading", progress, speed, seeds, seeds,
                1, START.minus(Duration.ofDays(5)).getEpochSecond(), 50_000_000_000L);
    }

    private TorrentAnalyticsProperties properties() {
        return new TorrentAnalyticsProperties(true, Duration.ofMinutes(1), Duration.ZERO,
                Path.of("unused.json"), Duration.ofDays(3), Duration.ofDays(3), 0.95,
                Duration.ofDays(1), 5, Duration.ofHours(6), Duration.ofDays(14), 0.10,
                DataSize.ofKilobytes(10));
    }

    private static class InMemoryStore implements TorrentAnalyticsStateStore {
        private Optional<TorrentAnalyticsState> state = Optional.empty();

        @Override
        public Optional<TorrentAnalyticsState> load() {
            return state;
        }

        @Override
        public void save(TorrentAnalyticsState state) {
            this.state = Optional.of(state);
        }
    }
}
