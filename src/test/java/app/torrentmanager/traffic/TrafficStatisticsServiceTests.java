package app.torrentmanager.traffic;

import app.torrentmanager.qbit.LifetimeTransferInfo;
import app.torrentmanager.qbit.QBitClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrafficStatisticsServiceTests {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Test
    void startsDailyCountersAtZeroAndAccumulatesDeltas() {
        QBitClient client = mock(QBitClient.class);
        when(client.getLifetimeTransferInfo())
                .thenReturn(new LifetimeTransferInfo(10_000, 2_000))
                .thenReturn(new LifetimeTransferInfo(13_500, 2_700));
        MemoryStore store = new MemoryStore();
        TrafficStatisticsService service = service(client, store, "2026-08-19T08:00:00Z");

        service.refresh();
        service.refresh();

        TrafficSnapshot snapshot = service.snapshot();
        assertThat(snapshot.downloadedTodayBytes()).isEqualTo(3_500);
        assertThat(snapshot.uploadedTodayBytes()).isEqualTo(700);
        assertThat(snapshot.downloadedAllTimeBytes()).isEqualTo(13_500);
        assertThat(snapshot.uploadedAllTimeBytes()).isEqualTo(2_700);
    }

    @Test
    void continuesFromPersistedStateAfterRestart() {
        MemoryStore store = new MemoryStore();
        store.state = new TrafficState(
                java.time.LocalDate.parse("2026-08-19"),
                10_000, 2_000, 4_000, 600,
                Instant.parse("2026-08-19T06:00:00Z"));
        QBitClient client = mock(QBitClient.class);
        when(client.getLifetimeTransferInfo()).thenReturn(new LifetimeTransferInfo(11_000, 2_250));

        TrafficStatisticsService service = service(client, store, "2026-08-19T12:00:00Z");
        service.refresh();

        assertThat(service.snapshot().downloadedTodayBytes()).isEqualTo(5_000);
        assertThat(service.snapshot().uploadedTodayBytes()).isEqualTo(850);
    }

    @Test
    void resetsDailyCountersAfterMoscowMidnight() {
        MemoryStore store = new MemoryStore();
        store.state = new TrafficState(
                java.time.LocalDate.parse("2026-08-19"),
                10_000, 2_000, 4_000, 600,
                Instant.parse("2026-08-19T06:00:00Z"));
        QBitClient client = mock(QBitClient.class);
        when(client.getLifetimeTransferInfo()).thenReturn(new LifetimeTransferInfo(11_000, 2_250));

        TrafficStatisticsService service = service(client, store, "2026-08-19T21:01:00Z");
        service.refresh();

        TrafficSnapshot snapshot = service.snapshot();
        assertThat(snapshot.date()).isEqualTo(LocalDate.parse("2026-08-20"));
        assertThat(snapshot.downloadedTodayBytes()).isZero();
        assertThat(snapshot.uploadedTodayBytes()).isZero();
        assertThat(snapshot.trackingSince()).isEqualTo(Instant.parse("2026-08-19T06:00:00Z"));
    }

    @Test
    void doesNotAddNegativeDeltaWhenQbittorrentCounterResets() {
        MemoryStore store = new MemoryStore();
        store.state = new TrafficState(
                java.time.LocalDate.parse("2026-08-19"),
                10_000, 2_000, 4_000, 600,
                Instant.parse("2026-08-19T06:00:00Z"));
        QBitClient client = mock(QBitClient.class);
        when(client.getLifetimeTransferInfo()).thenReturn(new LifetimeTransferInfo(100, 50));

        TrafficStatisticsService service = service(client, store, "2026-08-19T12:00:00Z");
        service.refresh();

        assertThat(service.snapshot().downloadedTodayBytes()).isEqualTo(4_000);
        assertThat(service.snapshot().uploadedTodayBytes()).isEqualTo(600);
    }

    private TrafficStatisticsService service(QBitClient client, MemoryStore store, String instant) {
        return new TrafficStatisticsService(client, store,
                Clock.fixed(Instant.parse(instant), MOSCOW));
    }

    private static class MemoryStore implements TrafficStateStore {
        private TrafficState state;

        @Override
        public Optional<TrafficState> load() {
            return Optional.ofNullable(state);
        }

        @Override
        public void save(TrafficState state) {
            this.state = state;
        }
    }
}
