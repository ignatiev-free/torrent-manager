package app.torrentmanager.monitoring;

import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.Torrent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DownloadMonitoringServiceTests {
    private static final Instant START = Instant.parse("2026-08-19T10:00:00Z");

    @Test
    void firstSnapshotDoesNotPublishCompletionForExistingTorrents() {
        InMemoryStore store = new InMemoryStore();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        DownloadMonitoringService service = service(store, publisher);

        service.inspectAt(List.of(incomplete("loading"), complete("already-complete")), START);

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
        assertThat(store.state.torrents()).hasSize(2);
    }

    @Test
    void publishesEventWhenKnownTorrentBecomesComplete() {
        InMemoryStore store = new InMemoryStore();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        DownloadMonitoringService service = service(store, publisher);
        service.inspectAt(List.of(incomplete("movie")), START);

        Instant completedAt = START.plusSeconds(60);
        service.inspectAt(List.of(complete("movie")), completedAt);
        service.inspectAt(List.of(complete("movie")), completedAt.plusSeconds(60));

        verify(publisher, times(1)).publishEvent(new DownloadCompletedEvent(
                "movie", "movie", completedAt, 3660L, 60L));
        assertThat(store.state.torrents().get("movie").complete()).isTrue();
    }

    @Test
    void restoredIncompleteTorrentCanCompleteAfterRestart() {
        InMemoryStore store = new InMemoryStore();
        store.state = new DownloadMonitorState(START,
                java.util.Map.of("movie", new MonitoredTorrent("movie", false)));
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        Instant completedAt = START.plusSeconds(300);
        service(store, publisher).inspectAt(List.of(complete("movie")), completedAt);

        verify(publisher).publishEvent(new DownloadCompletedEvent(
                "movie", "movie", completedAt, 3900L, 0L));
    }

    @Test
    void newlyAddedCompletedTorrentIsOnlyAddedToBaseline() {
        InMemoryStore store = new InMemoryStore();
        store.state = new DownloadMonitorState(START, java.util.Map.of());
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        service(store, publisher).inspectAt(List.of(complete("new-complete")), START.plusSeconds(60));

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
        assertThat(store.state.torrents()).containsKey("new-complete");
    }

    @Test
    void removedTorrentsAreDroppedFromState() {
        InMemoryStore store = new InMemoryStore();
        store.state = new DownloadMonitorState(START,
                java.util.Map.of("removed", new MonitoredTorrent("removed", false)));

        service(store, mock(ApplicationEventPublisher.class))
                .inspectAt(List.of(incomplete("remaining")), START.plusSeconds(60));

        assertThat(store.state.torrents()).containsOnlyKeys("remaining");
    }

    private DownloadMonitoringService service(InMemoryStore store, ApplicationEventPublisher publisher) {
        return new DownloadMonitoringService(mock(QBitClient.class), store, publisher,
                Clock.fixed(START, ZoneOffset.UTC));
    }

    private Torrent incomplete(String hash) {
        return new Torrent(hash, hash, "downloading", 0.5, 1024, 1, 1, 1,
                START.minusSeconds(3600).getEpochSecond(), 1000);
    }

    private Torrent complete(String hash) {
        return new Torrent(hash, hash, "uploading", 1.0, 0, 1, 1, 1,
                START.minusSeconds(3600).getEpochSecond(), 1000);
    }

    private static class InMemoryStore implements DownloadMonitorStateStore {
        private DownloadMonitorState state;

        @Override
        public Optional<DownloadMonitorState> load() {
            return Optional.ofNullable(state);
        }

        @Override
        public void save(DownloadMonitorState state) {
            this.state = state;
        }
    }
}
