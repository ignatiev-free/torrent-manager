package app.torrentmanager.monitoring;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record DownloadMonitorState(Instant initializedAt, Map<String, MonitoredTorrent> torrents) {
    public DownloadMonitorState {
        torrents = Map.copyOf(new LinkedHashMap<>(torrents));
    }
}
