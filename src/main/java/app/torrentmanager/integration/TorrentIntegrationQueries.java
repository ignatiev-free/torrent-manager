package app.torrentmanager.integration;

import app.torrentmanager.analytics.TorrentAnalyticsService;
import app.torrentmanager.manager.ManagerStateStore;
import app.torrentmanager.monitoring.DownloadCompletionHistoryService;
import app.torrentmanager.notification.SpeedSnapshotService;
import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.Torrent;
import app.torrentmanager.report.DailyReportService;
import app.torrentmanager.report.StorageCapacityService;
import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TorrentIntegrationQueries {
    private final QBitClient qbit;
    private final SpeedSnapshotService speed;
    private final ManagerStateStore queue;
    private final TorrentAnalyticsService analytics;
    private final DownloadCompletionHistoryService history;
    private final StorageCapacityService storage;
    private final DailyReportService reports;

    public TorrentIntegrationQueries(QBitClient qbit, SpeedSnapshotService speed, ManagerStateStore queue,
             TorrentAnalyticsService analytics, DownloadCompletionHistoryService history,
             StorageCapacityService storage, DailyReportService reports) {
        this.qbit = qbit;
        this.speed = speed;
        this.queue = queue;
        this.analytics = analytics;
        this.history = history;
        this.storage = storage;
        this.reports = reports;
    }

    Overview overview() {
        var list = qbit.getTorrents();
        return new Overview(speed.read(), list.stream().filter(Torrent::isActivelyDownloading)
                .filter(t -> t.downloadSpeed() > 0).count(), list.stream().filter(Torrent::isActivelyDownloading)
                .filter(t -> t.downloadSpeed() <= 0).count(), list.stream().filter(Torrent::isQueuedForDownload).count());
    }

    Page<Download> downloads(int page, String query) {
        if (query.length() > 160) throw new IllegalArgumentException("Search query is too long");
        String name = query.strip().toLowerCase(java.util.Locale.ROOT);
        var retries = queue.load();
        var records = analytics.snapshot().stream().filter(r -> r.lastProgressAt() != null)
                .collect(Collectors.toMap(r -> r.hash(), r -> r.lastProgressAt(), (left, right) -> left));
        var rows = qbit.getTorrents().stream()
                .filter(t -> !name.isEmpty() ? t.name().toLowerCase(java.util.Locale.ROOT).contains(name)
                        : t.isActivelyDownloading() || t.isQueuedForDownload()
                        || (!t.isComplete() && retries.containsKey(t.hash())
                            && ("stoppedDL".equals(t.state()) || "pausedDL".equals(t.state()))))
                .sorted(Comparator.comparingLong(Torrent::downloadSpeed).reversed().thenComparing(Torrent::hash))
                .map(t -> new Download(t.name(), t.state(), t.progress(), t.downloadSpeed(), t.size(),
                        t.downloadSpeed() > 0 && t.amountLeft() > 0
                                ? Math.ceilDiv(t.amountLeft(), t.downloadSpeed()) : null,
                        t.connectedSeeds(), t.availableSeeds(), t.addedOn(), records.get(t.hash()), retries.get(t.hash())))
                .toList();
        return page(rows, page);
    }

    Page<Completion> completions(int page) {
        var cutoff = Instant.now().minus(Duration.ofHours(24));
        return page(history.recent(Integer.MAX_VALUE).stream().filter(r -> !r.completedAt().isBefore(cutoff))
                .map(r -> new Completion(r.id(), r.name(), r.completedAt())).toList(), page);
    }

    Storage storage() {
        var snapshot = storage.snapshot();
        if (snapshot.error() != null) throw new IllegalStateException("Storage unavailable");
        return new Storage(snapshot.enabled(), snapshot.disks().stream().map(d -> new Disk(d.name(), d.available(),
                d.freeBytes(), d.remainingBytes(), d.projectedFreeBytes(), d.unknownSizeTorrents())).toList(),
                snapshot.unmatchedTorrents(), snapshot.unmatchedRemainingBytes());
    }

    DailyReportService.IntegrationStatus reports() { return reports.integrationStatus(); }

    boolean sendReport() { return reports.sendManual(); }

    private static <T> Page<T> page(List<T> items, int page) {
        if (page < 0 || page > 10000) throw new IllegalArgumentException("Invalid page");
        int from = Math.min(page * 10, items.size());
        int to = Math.min(from + 10, items.size());
        return new Page<>(items.subList(from, to), page, to < items.size(), items.size());
    }

    public record Overview(SpeedSnapshotService.SpeedSnapshot speed, long downloading, long idle, long queued) {}
    public record Page<T>(List<T> items, int page, boolean more, int total) {}
    public record Download(String name, String state, double progress, long downloadBps, long sizeBytes, Long etaSeconds,
                           int connectedSeeds, int availableSeeds, long addedEpochSeconds, Instant lastProgressAt,
                           Instant managerRetryAt) {}
    public record Completion(UUID id, String name, Instant completedAt) {}
    public record Disk(String name, boolean available, long freeBytes, long remainingBytes, long projectedFreeBytes,
                       int unknownSizeTorrents) {}
    public record Storage(boolean enabled, List<Disk> disks, int unmatchedTorrents, long unmatchedRemainingBytes) {}
}
