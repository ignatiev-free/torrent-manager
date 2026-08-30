package app.torrentmanager.report;

import java.util.List;

public record StorageCapacitySnapshot(
        boolean enabled,
        List<DiskCapacity> disks,
        int unmatchedTorrents,
        long unmatchedRemainingBytes,
        String error
) {
    public StorageCapacitySnapshot {
        disks = disks == null ? List.of() : List.copyOf(disks);
    }

    public static StorageCapacitySnapshot disabled() {
        return new StorageCapacitySnapshot(false, List.of(), 0, 0, null);
    }

    public static StorageCapacitySnapshot failed(String error) {
        return new StorageCapacitySnapshot(true, List.of(), 0, 0, error);
    }
}
