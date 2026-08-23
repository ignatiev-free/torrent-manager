package app.torrentmanager.monitoring;

import java.time.Instant;

public record MonitoredTorrent(String name, boolean complete, Instant addedAt,
                               Instant lastObservedAt, long activeSeconds) {
    public MonitoredTorrent(String name, boolean complete) {
        this(name, complete, null, null, 0);
    }
}
