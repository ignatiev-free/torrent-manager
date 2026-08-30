package app.torrentmanager.report;

public record DiskCapacity(
        String name,
        long totalBytes,
        long freeBytes,
        int incompleteTorrents,
        long remainingBytes,
        int unknownSizeTorrents,
        String error
) {
    public long projectedFreeBytes() {
        return freeBytes - remainingBytes;
    }

    public boolean available() {
        return error == null;
    }
}
