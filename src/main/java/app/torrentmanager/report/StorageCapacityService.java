package app.torrentmanager.report;

import app.torrentmanager.config.StorageReportProperties;
import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.Torrent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

@Service
public class StorageCapacityService {
    private static final Logger log = LoggerFactory.getLogger(StorageCapacityService.class);
    private final QBitClient qbit;
    private final StorageReportProperties properties;
    private final FileSpaceProbe fileSpaceProbe;

    public StorageCapacityService(QBitClient qbit, StorageReportProperties properties,
                                  FileSpaceProbe fileSpaceProbe) {
        this.qbit = qbit;
        this.properties = properties;
        this.fileSpaceProbe = fileSpaceProbe;
    }

    public StorageCapacitySnapshot snapshot() {
        if (!properties.enabled()) {
            return StorageCapacitySnapshot.disabled();
        }
        if (properties.disks().isEmpty()) {
            return StorageCapacitySnapshot.failed("диски не настроены");
        }
        try {
            List<Torrent> incomplete = qbit.getTorrents().stream()
                    .filter(torrent -> !torrent.isComplete())
                    .toList();
            List<MutableDisk> disks = properties.disks().stream()
                    .map(MutableDisk::new)
                    .toList();
            int unmatched = 0;
            long unmatchedBytes = 0;
            for (Torrent torrent : incomplete) {
                MutableDisk disk = matchingDisk(disks, torrent.savePath());
                if (disk == null) {
                    unmatched++;
                    unmatchedBytes = saturatedAdd(unmatchedBytes, Math.max(0, torrent.amountLeft()));
                } else {
                    disk.incompleteTorrents++;
                    if (torrent.size() <= 0 || "metaDL".equals(torrent.state())) {
                        disk.unknownSizeTorrents++;
                    } else {
                        disk.remainingBytes = saturatedAdd(disk.remainingBytes,
                                Math.max(0, torrent.amountLeft()));
                    }
                }
            }
            List<DiskCapacity> snapshots = disks.stream().map(this::snapshot).toList();
            return new StorageCapacitySnapshot(true, snapshots, unmatched, unmatchedBytes, null);
        } catch (RuntimeException exception) {
            String error = safeError(exception);
            log.warn("Не удалось рассчитать свободное место для отчёта: {}", error);
            return StorageCapacitySnapshot.failed(error);
        }
    }

    private MutableDisk matchingDisk(List<MutableDisk> disks, String savePath) {
        if (savePath == null || savePath.isBlank()) {
            return null;
        }
        String normalized = normalize(savePath);
        return disks.stream()
                .filter(disk -> isWithin(normalized, normalize(disk.configuration.qbitPath())))
                .max(Comparator.comparingInt(disk -> normalize(
                        disk.configuration.qbitPath()).length()))
                .orElse(null);
    }

    private boolean isWithin(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private String normalize(String path) {
        String value = path.replace('\\', '/');
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private DiskCapacity snapshot(MutableDisk disk) {
        try {
            FileSpaceProbe.Space space = fileSpaceProbe.read(disk.configuration.mountPath());
            return new DiskCapacity(disk.configuration.name(), space.totalBytes(),
                    space.freeBytes(), disk.incompleteTorrents, disk.remainingBytes,
                    disk.unknownSizeTorrents, null);
        } catch (IOException exception) {
            return new DiskCapacity(disk.configuration.name(), 0, 0,
                    disk.incompleteTorrents, disk.remainingBytes, disk.unknownSizeTorrents,
                    safeError(exception));
        }
    }

    private long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private String safeError(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static final class MutableDisk {
        private final StorageReportProperties.Disk configuration;
        private int incompleteTorrents;
        private long remainingBytes;
        private int unknownSizeTorrents;

        private MutableDisk(StorageReportProperties.Disk configuration) {
            this.configuration = configuration;
        }
    }
}
