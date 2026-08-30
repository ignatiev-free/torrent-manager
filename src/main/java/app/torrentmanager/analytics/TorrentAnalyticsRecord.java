package app.torrentmanager.analytics;

import app.torrentmanager.qbit.Torrent;

import java.time.Duration;
import java.time.Instant;

public record TorrentAnalyticsRecord(
        String hash,
        String name,
        Instant addedAt,
        Instant firstObservedAt,
        Instant lastObservedAt,
        String state,
        double progress,
        Instant lastProgressAt,
        long activeSeconds,
        long queuedSeconds,
        Instant noSeedsSince,
        long downloadSpeed,
        int connectedSeeds,
        int availableSeeds,
        int managerStops,
        int managerResumes,
        int fairRotations,
        int attemptsSinceProgress
) {
    private static final double PROGRESS_EPSILON = 0.000001;

    public static TorrentAnalyticsRecord first(Torrent torrent, Instant now) {
        Instant addedAt = torrent.addedOn() > 0 ? Instant.ofEpochSecond(torrent.addedOn()) : now;
        Instant noSeedsSince = hasNoSeeds(torrent) ? now : null;
        return new TorrentAnalyticsRecord(torrent.hash(), torrent.name(), addedAt, now, now,
                torrent.state(), torrent.progress(), now, 0, 0, noSeedsSince,
                torrent.downloadSpeed(), torrent.connectedSeeds(), torrent.availableSeeds(),
                0, 0, 0, 0);
    }

    public static TorrentAnalyticsRecord first(ManagerActionEvent event) {
        TorrentAnalyticsRecord initial = new TorrentAnalyticsRecord(event.hash(), event.name(),
                event.at(), event.at(), event.at(), "unknown", event.progress(), event.at(),
                0, 0, null, 0, 0, 0, 0, 0, 0, 0);
        return initial.action(event.type(), event.at());
    }

    public TorrentAnalyticsRecord observe(Torrent torrent, Instant now, Duration maximumElapsed) {
        long elapsed = Math.max(0, Duration.between(lastObservedAt, now).getSeconds());
        elapsed = Math.min(elapsed, Math.max(0, maximumElapsed.getSeconds()));
        long nextActive = activeSeconds + (isActiveState(state) ? elapsed : 0);
        long nextQueued = queuedSeconds + ("queuedDL".equals(state) ? elapsed : 0);
        boolean progressed = torrent.progress() > progress + PROGRESS_EPSILON;
        Instant nextNoSeedsSince = hasNoSeeds(torrent)
                ? (noSeedsSince == null ? now : noSeedsSince)
                : null;
        return new TorrentAnalyticsRecord(hash, torrent.name(), addedAt, firstObservedAt, now,
                torrent.state(), torrent.progress(), progressed ? now : lastProgressAt,
                nextActive, nextQueued, nextNoSeedsSince, torrent.downloadSpeed(),
                torrent.connectedSeeds(), torrent.availableSeeds(), managerStops, managerResumes,
                fairRotations, progressed ? 0 : attemptsSinceProgress);
    }

    public TorrentAnalyticsRecord action(ManagerActionType type, Instant now) {
        int stops = managerStops;
        int resumes = managerResumes;
        int rotations = fairRotations;
        int attempts = attemptsSinceProgress;
        if (type == ManagerActionType.STOPPED) {
            stops++;
        } else {
            resumes++;
            attempts++;
            if (type == ManagerActionType.FAIR_ROTATION) {
                rotations++;
            }
        }
        return new TorrentAnalyticsRecord(hash, name, addedAt, firstObservedAt,
                lastObservedAt == null ? now : lastObservedAt, state, progress, lastProgressAt,
                activeSeconds, queuedSeconds, noSeedsSince, downloadSpeed, connectedSeeds,
                availableSeeds, stops, resumes, rotations, attempts);
    }

    public boolean activeOrQueued() {
        return isActiveState(state) || "queuedDL".equals(state);
    }

    public boolean forced() {
        return "forcedDL".equals(state);
    }

    private static boolean hasNoSeeds(Torrent torrent) {
        return torrent.connectedSeeds() <= 0 && torrent.availableSeeds() <= 0;
    }

    private static boolean isActiveState(String state) {
        return switch (state) {
            case "downloading", "stalledDL", "metaDL", "forcedDL", "checkingDL", "allocating" -> true;
            default -> false;
        };
    }
}
