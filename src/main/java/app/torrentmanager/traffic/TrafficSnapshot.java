package app.torrentmanager.traffic;

import java.time.Instant;
import java.time.LocalDate;

public record TrafficSnapshot(
        LocalDate date,
        long downloadedTodayBytes,
        long uploadedTodayBytes,
        long downloadedAllTimeBytes,
        long uploadedAllTimeBytes,
        Instant trackingSince
) {
}
