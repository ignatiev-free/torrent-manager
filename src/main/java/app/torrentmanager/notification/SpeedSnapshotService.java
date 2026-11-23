package app.torrentmanager.notification;

import app.torrentmanager.qbit.QBitClient;
import java.nio.file.*;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SpeedSnapshotService {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(SpeedSnapshotService.class);
    private final QBitClient qbit;
    private UUID generation = UUID.randomUUID();
    private final Path timerPath;
    private final Path pendingPath;

    public SpeedSnapshotService(QBitClient qbit,
                                @Value("${telegram-notifications.command-state-file}") Path commandState) {
        this.qbit = qbit;
        this.timerPath = SpeedTimer.pathFor(commandState);
        this.pendingPath = timerPath.resolveSibling("telegram-speed-transition");
    }

    public SpeedSnapshot read() {
        synchronized (qbit) {
            var transfer = qbit.getTransferInfo();
            var mode = transfer.alternativeSpeedLimitsEnabled();
            boolean alternative = mode == null ? qbit.alternativeSpeedMode() : mode;
            var timer = SpeedTimer.read(timerPath);
            Instant restoreAt = timer != null && timer.applied() == transfer.downloadRateLimit()
                    && timer.alternative() == alternative ? timer.until() : null;
            return new SpeedSnapshot(transfer.downloadSpeed(), transfer.uploadSpeed(), transfer.downloadRateLimit(),
                    transfer.uploadRateLimit(), alternative, restoreAt, Instant.now(), revision(alternative, transfer.downloadRateLimit(), timer));
        }
    }

    public record SpeedSnapshot(long downloadBps, long uploadBps, long downloadLimitBps, long uploadLimitBps,
                                boolean alternativeMode, Instant restoreAt, Instant observedAt, String revision) { }

    public enum Profile {
        MAX(0), WORK(8000 * 1024L), MIN(3000 * 1024L);

        final long bytesPerSecond;
        Profile(long bytesPerSecond) { this.bytesPerSecond = bytesPerSecond; }
    }
    public enum Period { HOUR, MORNING, MANUAL }
    public record Change(Profile profile, Period duration, String expectedRevision) { }
    public static final class StaleSpeed extends RuntimeException { }

    public SpeedSnapshot change(Change change) {
        if (change.profile() == null || change.duration() == null || change.expectedRevision() == null) {
            throw new IllegalArgumentException("Invalid speed change");
        }
        synchronized (qbit) {
            reconcile();
            var before = read();
            if (!before.revision().equals(change.expectedRevision())) throw new StaleSpeed();
            long desired = change.profile().bytesPerSecond;
            applySpeed(desired, change.duration(), before.alternativeMode(), before.downloadLimitBps());
            var applied = read();
            if (applied.downloadLimitBps() != desired || applied.alternativeMode() != before.alternativeMode()) {
                throw new IllegalStateException("Speed not verified");
            }
            return applied;
        }
    }

    void applyLegacy(long desired, String period) {
        synchronized (qbit) {
            reconcile();
            var duration = Period.valueOf(period.toUpperCase(java.util.Locale.ROOT));
            applySpeed(desired, duration, qbit.alternativeSpeedMode(), qbit.getTransferInfo().downloadRateLimit());
        }
    }

    SpeedTimer timer() { return SpeedTimer.read(timerPath); }

    private String revision(boolean alternative, long limit, SpeedTimer timer) {
        String state = generation + ":" + alternative + ":" + limit + ":" + timer;
        return UUID.nameUUIDFromBytes(state.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private void applySpeed(long desired, Period period, boolean alternative, long current) {
        Instant now = Instant.now();
        Instant until = switch (period) {
            case MANUAL -> null;
            case HOUR -> now.plus(Duration.ofHours(1));
            case MORNING -> {
                var local = now.atZone(ZoneId.systemDefault());
                var morning = local.toLocalDate().atTime(8, 0).atZone(local.getZone());
                yield (morning.isAfter(local) ? morning : morning.plusDays(1)).toInstant();
            }
        };
        SpeedTimer existing = timer();
        long previous = existing != null && existing.alternative() == alternative && existing.applied() == current
                ? existing.previous() : current;
        save(pendingPath, previous + " " + desired + " " + alternative + " " + (until == null ? "-" : until));
        generation = UUID.randomUUID();
        try {
            qbit.setDownloadLimit(desired, alternative);
        } catch (RuntimeException failure) {
            try {
                reconcile();
            } catch (RuntimeException unresolved) {
                // The pending record remains available for the next scheduler cycle.
            }
            throw failure;
        }
        if (!reconcile()) throw new IllegalStateException("Speed not verified");
    }

    private boolean reconcile() {
        if (!Files.exists(pendingPath)) return false;
        try {
            String[] fields = Files.readString(pendingPath).strip().split(" ");
            long desired = Long.parseLong(fields[1]);
            boolean alternative = Boolean.parseBoolean(fields[2]);
            var transfer = qbit.getTransferInfo();
            var mode = transfer.alternativeSpeedLimitsEnabled();
            boolean actualMode = mode == null ? qbit.alternativeSpeedMode() : mode;
            boolean applied = actualMode == alternative && transfer.downloadRateLimit() == desired;
            if (applied) {
                if ("-".equals(fields[3])) clearTimer();
                else save(timerPath, String.join(" ", fields));
            }
            Files.deleteIfExists(pendingPath);
            return applied;
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15000)
    void restoreSpeed() {
        synchronized (qbit) {
            try {
                reconcile();
                SpeedTimer timer = SpeedTimer.read(timerPath);
                if (timer == null) return;
                boolean alternative = qbit.alternativeSpeedMode();
                long current = qbit.getTransferInfo().downloadRateLimit();
                if (alternative != timer.alternative() || current != timer.applied()) {
                    clearTimer();
                } else if (!Instant.now().isBefore(timer.until())) {
                    qbit.setDownloadLimit(timer.previous(), alternative);
                    if (qbit.getTransferInfo().downloadRateLimit() == timer.previous()) clearTimer();
                }
            } catch (RuntimeException exception) {
                log.warn("Не удалось проверить таймер скорости: {}", exception.getClass().getSimpleName());
            }
        }
    }

    private void save(Path target, String value) {
        try {
            Files.createDirectories(timerPath.getParent());
            Path temp = Files.createTempFile(timerPath.getParent(), "telegram-speed-", ".tmp");
            try {
                Files.writeString(temp, value);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private void clearTimer() {
        try {
            Files.deleteIfExists(timerPath);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

}
