package app.torrentmanager.manager;

import app.torrentmanager.config.ManagerProperties;
import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.Torrent;
import app.torrentmanager.qbit.TransferInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class TorrentManagerService {
    private static final Logger log = LoggerFactory.getLogger(TorrentManagerService.class);
    private static final DateTimeFormatter LOG_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");

    private final QBitClient client;
    private final ManagerProperties properties;
    private final Clock clock;
    private final ManagerStateStore stateStore;
    private final Map<String, ArrayDeque<SpeedSample>> speedHistory = new HashMap<>();
    private final Map<String, Instant> observedSince = new HashMap<>();
    private final Map<String, Instant> noSeedsSince = new HashMap<>();
    private final Map<String, Instant> pausedByManager = new LinkedHashMap<>();
    private final Set<String> previouslyActive = new HashSet<>();
    private final Set<String> startedByManager = new HashSet<>();
    private final Set<String> fairRotationCandidates = new HashSet<>();
    private boolean activeSnapshotInitialized;
    private Long lastDownloadRateLimit;
    private Instant lastActionAt;

    @Autowired
    public TorrentManagerService(QBitClient client, ManagerProperties properties, ManagerStateStore stateStore) {
        this(client, properties, Clock.systemDefaultZone(), stateStore);
    }

    TorrentManagerService(QBitClient client, ManagerProperties properties, Clock clock) {
        this(client, properties, clock, ManagerStateStore.noOp());
    }

    TorrentManagerService(QBitClient client, ManagerProperties properties, Clock clock,
                          ManagerStateStore stateStore) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.stateStore = stateStore;
        this.pausedByManager.putAll(stateStore.load());
    }

    @Scheduled(fixedDelayString = "${torrent-manager.poll-interval}")
    public void inspect() {
        try {
            inspectAt(clock.instant());
        } catch (Exception exception) {
            log.error("Ошибка проверки qBittorrent: {}", exception.getMessage(), exception);
        }
    }

    void inspectAt(Instant now) {
        TransferInfo transferInfo = client.getTransferInfo();
        SpeedPolicy policy = speedPolicy(transferInfo);
        handleRateLimitChange(transferInfo.downloadRateLimit(), policy);

        List<Torrent> torrents = client.getTorrents();
        Map<String, Torrent> torrentsByHash = torrents.stream()
                .collect(Collectors.toMap(Torrent::hash, torrent -> torrent));
        Set<String> existingHashes = torrents.stream().map(Torrent::hash).collect(Collectors.toSet());
        speedHistory.keySet().retainAll(existingHashes);
        observedSince.keySet().retainAll(existingHashes);
        noSeedsSince.keySet().retainAll(existingHashes);
        pausedByManager.keySet().retainAll(existingHashes);
        fairRotationCandidates.removeIf(hash -> !isActiveOrQueued(torrentsByHash.get(hash)));

        List<Torrent> active = torrents.stream().filter(Torrent::isActivelyDownloading).toList();
        logNewlyActive(active);
        for (Torrent torrent : active) {
            record(torrent, now, policy.observationWindow());
        }

        List<Torrent> managedActive = active.stream().filter(Predicate.not(Torrent::isForced)).toList();
        Set<Torrent> toStop = new LinkedHashSet<>(managedActive.stream()
                .filter(torrent -> isTooSlow(torrent, now, policy, transferInfo)
                        || hasNoSeedsForTooLong(torrent, now))
                .toList());

        List<Torrent> healthy = managedActive.stream().filter(Predicate.not(toStop::contains)).toList();
        int excess = Math.max(0, healthy.size() - properties.maxActiveDownloads());
        healthy.stream()
                .filter(torrent -> hasFullObservationWindow(torrent, now, policy.observationWindow()))
                .sorted(Comparator.comparingDouble(this::averageSpeed))
                .limit(excess)
                .forEach(toStop::add);

        List<Torrent> limitedStops = toStop.stream().limit(properties.maxStopsPerCycle()).toList();
        if (!properties.dryRun() && !actionAllowed(now)) {
            limitedStops = List.of();
        }

        for (Torrent torrent : limitedStops) {
            String reason = hasNoSeedsForTooLong(torrent, now) ? "нет доступных сидов"
                    : isTooSlow(torrent, now, policy, transferInfo) ? "низкая средняя скорость"
                    : "превышен лимит активных загрузок";
            long averageKiB = Math.round(averageSpeed(torrent) / 1024.0);
            if (properties.dryRun()) {
                log.warn("Тестовый режим: торрент '{}' был бы остановлен "
                                + "(причина={}, средняя скорость={} KiB/s, порог={} KiB/s, сиды={}/{}, состояние={})",
                        torrent.name(), reason, averageKiB, policy.thresholdBytesPerSecond() / 1024,
                        torrent.connectedSeeds(), torrent.availableSeeds(), torrent.state());
            } else {
                client.stop(torrent.hash());
                pausedByManager.put(torrent.hash(), now.plus(properties.retryCooldown()));
                fairRotationCandidates.remove(torrent.hash());
                stateStore.save(pausedByManager);
                lastActionAt = now;
                log.info("Торрент '{}' остановлен (причина={}, средняя скорость={} KiB/s, сиды={}/{}); "
                                + "следующая попытка после {}",
                        torrent.name(), reason, averageKiB, torrent.connectedSeeds(), torrent.availableSeeds(),
                        formatTime(now.plus(properties.retryCooldown())));
                clearObservation(torrent.hash());
            }
        }

        boolean hasQueuedDownloads = torrents.stream().anyMatch(Torrent::isQueuedForDownload);
        if (!properties.dryRun() && limitedStops.isEmpty() && actionAllowed(now)) {
            int genuinelyFreeSlots = Math.max(0, properties.maxActiveDownloads() - managedActive.size());
            int fairVacancies = Math.max(0,
                    properties.fairRotationSlots() - fairRotationCandidates.size());
            int promoted = retryEligible(torrentsByHash, fairVacancies, now, true);
            int normallyStarted = 0;
            if (!hasQueuedDownloads) {
                normallyStarted = retryEligible(torrentsByHash,
                        Math.max(0, genuinelyFreeSlots - promoted), now, false);
            }
            if (promoted + normallyStarted > 0) {
                lastActionAt = now;
            }
        }

        long forced = active.stream().filter(Torrent::isForced).count();
        log.info("Проверено торрентов: {}; активных под управлением: {}; принудительных: {}; "
                        + "приостановленных менеджером: {}; скорость={} KiB/s; лимит={}; порог={} KiB/s; "
                        + "окно={}; тестовый режим={}",
                torrents.size(), managedActive.size(), forced, pausedByManager.size(),
                transferInfo.downloadSpeed() / 1024, formatLimit(transferInfo.downloadRateLimit()),
                policy.thresholdBytesPerSecond() / 1024, policy.observationWindow(), properties.dryRun());
    }

    private SpeedPolicy speedPolicy(TransferInfo transferInfo) {
        if (!transferInfo.isDownloadLimited()) {
            return new SpeedPolicy(properties.slowWindow(), properties.minimumDownloadSpeed().toBytes());
        }
        long proportionalThreshold = Math.round(
                transferInfo.downloadRateLimit()
                        / (double) Math.max(1, properties.maxActiveDownloads())
                        * properties.limitedThresholdRatio());
        long threshold = Math.max(properties.minimumLimitedDownloadSpeed().toBytes(),
                Math.min(properties.minimumDownloadSpeed().toBytes(), proportionalThreshold));
        return new SpeedPolicy(properties.limitedSlowWindow(), threshold);
    }

    private void handleRateLimitChange(long downloadRateLimit, SpeedPolicy policy) {
        if (lastDownloadRateLimit != null && lastDownloadRateLimit != downloadRateLimit) {
            speedHistory.clear();
            observedSince.clear();
            noSeedsSince.clear();
            log.info("Лимит загрузки изменён с {} на {}; измерения скорости начаты заново",
                    formatLimit(lastDownloadRateLimit), formatLimit(downloadRateLimit));
        } else if (lastDownloadRateLimit == null) {
            log.info("Выбран профиль скорости: лимит={}, порог={} KiB/s, окно={}",
                    formatLimit(downloadRateLimit), policy.thresholdBytesPerSecond() / 1024,
                    policy.observationWindow());
        }
        lastDownloadRateLimit = downloadRateLimit;
    }

    private void record(Torrent torrent, Instant now, Duration observationWindow) {
        observedSince.putIfAbsent(torrent.hash(), now);
        ArrayDeque<SpeedSample> samples = speedHistory.computeIfAbsent(torrent.hash(), ignored -> new ArrayDeque<>());
        samples.addLast(new SpeedSample(now, torrent.downloadSpeed()));
        Instant cutoff = now.minus(observationWindow);
        while (samples.size() > 2 && samples.getFirst().at().isBefore(cutoff)) {
            samples.removeFirst();
        }
        if (torrent.availableSeeds() <= 0 && torrent.connectedSeeds() <= 0) {
            noSeedsSince.putIfAbsent(torrent.hash(), now);
        } else {
            noSeedsSince.remove(torrent.hash());
        }
    }

    private boolean isTooSlow(Torrent torrent, Instant now, SpeedPolicy policy, TransferInfo transferInfo) {
        if (!hasFullObservationWindow(torrent, now, policy.observationWindow())) {
            return false;
        }
        if (isLimitedChannelSaturated(transferInfo)) {
            return false;
        }
        return averageSpeed(torrent) < policy.thresholdBytesPerSecond();
    }

    private boolean isLimitedChannelSaturated(TransferInfo transferInfo) {
        return transferInfo.isDownloadLimited()
                && transferInfo.downloadSpeed() >= transferInfo.downloadRateLimit() * properties.saturatedLimitRatio();
    }

    private boolean hasFullObservationWindow(Torrent torrent, Instant now, Duration observationWindow) {
        ArrayDeque<SpeedSample> samples = speedHistory.get(torrent.hash());
        Instant since = observedSince.get(torrent.hash());
        return samples != null && samples.size() >= 2 && since != null
                && !since.isAfter(now.minus(observationWindow));
    }

    private double averageSpeed(Torrent torrent) {
        ArrayDeque<SpeedSample> samples = speedHistory.get(torrent.hash());
        return samples == null ? 0 : samples.stream()
                .mapToLong(SpeedSample::bytesPerSecond).average().orElse(0);
    }

    private boolean hasNoSeedsForTooLong(Torrent torrent, Instant now) {
        Instant since = noSeedsSince.get(torrent.hash());
        return since != null && !since.isAfter(now.minus(properties.noSeedsTimeout()));
    }

    private boolean actionAllowed(Instant now) {
        return lastActionAt == null || !now.isBefore(lastActionAt.plus(properties.minimumActionInterval()));
    }

    private int retryEligible(Map<String, Torrent> torrentsByHash, int slots, Instant now,
                              boolean fairRotation) {
        if (slots == 0) {
            return 0;
        }
        List<String> eligible = pausedByManager.entrySet().stream()
                .filter(entry -> !entry.getValue().isAfter(now))
                .filter(entry -> isStoppedAndIncomplete(torrentsByHash.get(entry.getKey())))
                .sorted(Map.Entry.comparingByValue())
                .limit(slots)
                .map(Map.Entry::getKey)
                .toList();
        for (String hash : eligible) {
            Torrent torrent = torrentsByHash.get(hash);
            if (fairRotation) {
                client.moveToTop(hash);
                fairRotationCandidates.add(hash);
            }
            client.start(hash);
            startedByManager.add(hash);
            pausedByManager.remove(hash);
            if (fairRotation) {
                log.info("Выделен слот справедливой ротации для '{}' (прогресс={}%, "
                                + "доступно слотов ротации={})",
                        torrent.name(), Math.round(torrent.progress() * 100),
                        properties.fairRotationSlots());
            } else {
                log.info("Повторный запуск '{}' после периода ожидания", torrent.name());
            }
        }
        if (!eligible.isEmpty()) {
            stateStore.save(pausedByManager);
        }
        return eligible.size();
    }

    private void logNewlyActive(List<Torrent> active) {
        Set<String> currentActive = active.stream().map(Torrent::hash).collect(Collectors.toSet());
        if (activeSnapshotInitialized) {
            active.stream()
                    .filter(torrent -> !previouslyActive.contains(torrent.hash()))
                    .filter(torrent -> !startedByManager.remove(torrent.hash()))
                    .forEach(torrent -> log.info("Обнаружен новый активный торрент '{}'", torrent.name()));
        }
        startedByManager.retainAll(currentActive);
        previouslyActive.clear();
        previouslyActive.addAll(currentActive);
        activeSnapshotInitialized = true;
    }

    private boolean isStoppedAndIncomplete(Torrent torrent) {
        return torrent != null && !torrent.isComplete()
                && (torrent.state().equals("stoppedDL") || torrent.state().equals("pausedDL"));
    }

    private boolean isActiveOrQueued(Torrent torrent) {
        return torrent != null && !torrent.isComplete()
                && (torrent.isActivelyDownloading() || torrent.isQueuedForDownload());
    }

    private void clearObservation(String hash) {
        speedHistory.remove(hash);
        observedSince.remove(hash);
        noSeedsSince.remove(hash);
    }

    String formatTime(Instant instant) {
        return LOG_TIME_FORMATTER.withZone(clock.getZone()).format(instant);
    }

    private String formatLimit(long bytesPerSecond) {
        return bytesPerSecond <= 0 ? "без ограничений" : bytesPerSecond / 1024 + " KiB/s";
    }

    private record SpeedPolicy(Duration observationWindow, long thresholdBytesPerSecond) {
    }

    private record SpeedSample(Instant at, long bytesPerSecond) {
    }
}
