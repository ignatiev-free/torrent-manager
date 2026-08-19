package app.torrentmanager.qbit;

public record LifetimeTransferInfo(long downloadedBytes, long uploadedBytes) {
    public LifetimeTransferInfo {
        if (downloadedBytes < 0 || uploadedBytes < 0) {
            throw new IllegalArgumentException("Счётчики трафика не могут быть отрицательными");
        }
    }
}
