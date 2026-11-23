package app.torrentmanager.notification;

import app.torrentmanager.qbit.QBitClient;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SpeedSnapshotService {
    private final QBitClient qbit;
    private final Path timerPath;

    public SpeedSnapshotService(QBitClient qbit,
                                @Value("${telegram-notifications.command-state-file}") Path commandState) {
        this.qbit = qbit;
        this.timerPath = SpeedTimer.pathFor(commandState);
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
                    transfer.uploadRateLimit(), alternative, restoreAt, Instant.now());
        }
    }

    public record SpeedSnapshot(long downloadBps, long uploadBps, long downloadLimitBps, long uploadLimitBps,
                                boolean alternativeMode, Instant restoreAt, Instant observedAt) { }
}
