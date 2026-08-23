package app.torrentmanager.analytics;

import java.util.Optional;

public interface TorrentAnalyticsStateStore {
    Optional<TorrentAnalyticsState> load();

    void save(TorrentAnalyticsState state);
}
