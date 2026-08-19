package app.torrentmanager.traffic;

import java.time.Instant;
import java.time.LocalDate;

public record TrafficState(
        LocalDate date,
        long lastDownloadedBytes,
        long lastUploadedBytes,
        long downloadedTodayBytes,
        long uploadedTodayBytes,
        Instant trackingSince
) {
}
