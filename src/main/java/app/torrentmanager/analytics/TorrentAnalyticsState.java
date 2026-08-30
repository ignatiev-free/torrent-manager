package app.torrentmanager.analytics;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record TorrentAnalyticsState(Instant updatedAt, Map<String, TorrentAnalyticsRecord> torrents) {
    public TorrentAnalyticsState {
        torrents = torrents == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(torrents));
    }
}
