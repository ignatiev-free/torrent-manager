package app.torrentmanager.qbit;

import java.util.List;

public interface QBitClient {
    default QueuePreferences getQueuePreferences() { throw new UnsupportedOperationException(); }

    default void setQueuePreferences(QueuePreferences preferences) { throw new UnsupportedOperationException(); }

    default boolean alternativeSpeedMode() { throw new UnsupportedOperationException(); }

    default void setAlternativeSpeedMode(boolean enabled) { throw new UnsupportedOperationException(); }

    List<Torrent> getTorrents();

    TransferInfo getTransferInfo();

    LifetimeTransferInfo getLifetimeTransferInfo();

    void stop(String hash);

    void start(String hash);

    void moveToTop(String hash);
}
