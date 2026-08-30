package app.torrentmanager.report;

import app.torrentmanager.analytics.ManagerActionHistoryService;
import app.torrentmanager.analytics.TorrentAnalyticsRecord;
import app.torrentmanager.analytics.TorrentAnalyticsService;
import app.torrentmanager.config.DailyReportProperties;
import app.torrentmanager.monitoring.DownloadCompletionHistoryService;
import app.torrentmanager.notification.NotificationMessage;
import app.torrentmanager.notification.NotificationSender;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyReportServiceTests {
    private static final Instant NOW = Instant.parse("2026-08-23T09:01:00Z");

    @Test
    void sendsScheduledReportOnceAfterConfiguredTime() {
        Fixture fixture = fixture();

        fixture.service.sendScheduledIfDue();
        fixture.service.sendScheduledIfDue();

        verify(fixture.sender, times(1)).send(any(NotificationMessage.class));
        assertThat(fixture.store.state).isPresent();
        assertThat(fixture.store.state.orElseThrow().lastDailyReportDate().toString())
                .isEqualTo("2026-08-23");
    }

    @Test
    void manualReportDoesNotMoveDailyBaselineOrDailyTimestamp() {
        Fixture fixture = fixture();
        fixture.service.sendScheduledIfDue();
        DailyReportState afterDaily = fixture.store.state.orElseThrow();

        fixture.service.sendManual();

        DailyReportState afterManual = fixture.store.state.orElseThrow();
        assertThat(afterManual.lastDailyReportAt()).isEqualTo(afterDaily.lastDailyReportAt());
        assertThat(afterManual.progressBaseline()).isEqualTo(afterDaily.progressBaseline());
        assertThat(afterManual.lastManualReportAt()).isEqualTo(NOW);
        verify(fixture.sender, times(2)).send(any(NotificationMessage.class));
    }

    private Fixture fixture() {
        DownloadCompletionHistoryService completions = mock(DownloadCompletionHistoryService.class);
        TorrentAnalyticsService analytics = mock(TorrentAnalyticsService.class);
        ManagerActionHistoryService actions = mock(ManagerActionHistoryService.class);
        DailyReportFormatter formatter = mock(DailyReportFormatter.class);
        NotificationSender sender = mock(NotificationSender.class);
        InMemoryStore store = new InMemoryStore();
        when(completions.recent(1000)).thenReturn(List.of());
        when(analytics.snapshot()).thenReturn(List.of(record()));
        when(actions.between(any(), any())).thenReturn(List.of());
        when(formatter.format(any(), any(), any(), any(), any(), any())).thenReturn("report");
        when(sender.channel()).thenReturn("EMAIL");
        DailyReportService service = new DailyReportService(completions, analytics, actions,
                formatter, store, sender, properties(), Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, sender, store);
    }

    private TorrentAnalyticsRecord record() {
        return new TorrentAnalyticsRecord("hash", "Movie", NOW, NOW, NOW, "downloading",
                0.5, NOW, 0, 0, null, 1024, 1, 1, 0, 0, 0, 0);
    }

    private DailyReportProperties properties() {
        return new DailyReportProperties(true, LocalTime.of(9, 0), Duration.ofMinutes(1),
                Duration.ZERO, Path.of("state.json"), Path.of("actions.json"), 5000,
                20, 20, true, "1234567890abcdef", Duration.ofMinutes(5));
    }

    private record Fixture(DailyReportService service, NotificationSender sender,
                           InMemoryStore store) { }

    private static final class InMemoryStore implements DailyReportStateStore {
        private Optional<DailyReportState> state = Optional.empty();

        @Override
        public Optional<DailyReportState> load() { return state; }

        @Override
        public void save(DailyReportState state) { this.state = Optional.of(state); }
    }
}
