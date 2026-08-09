package app.torrentmanager.qbit;

import java.util.List;

public interface QBitClient {
    List<Torrent> getTorrents();

    TransferInfo getTransferInfo();

    void stop(String hash);

    void start(String hash);
}
