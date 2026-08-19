package app.torrentmanager.traffic;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

public record TrafficStatsResponse(
        LocalDate date,
        String downloadToday,
        String uploadToday,
        String downloadAllTime,
        String uploadAllTime,
        long downloadTodayBytes,
        long uploadTodayBytes,
        long downloadAllTimeBytes,
        long uploadAllTimeBytes,
        Instant trackingSince
) {
    static TrafficStatsResponse from(TrafficSnapshot snapshot) {
        return new TrafficStatsResponse(
                snapshot.date(),
                formatBytes(snapshot.downloadedTodayBytes()),
                formatBytes(snapshot.uploadedTodayBytes()),
                formatBytes(snapshot.downloadedAllTimeBytes()),
                formatBytes(snapshot.uploadedAllTimeBytes()),
                snapshot.downloadedTodayBytes(),
                snapshot.uploadedTodayBytes(),
                snapshot.downloadedAllTimeBytes(),
                snapshot.uploadedAllTimeBytes(),
                snapshot.trackingSince());
    }

    static String formatBytes(long bytes) {
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        int decimals = unit == 0 ? 0 : 1;
        return String.format(Locale.ROOT, "%." + decimals + "f %s", value, units[unit]);
    }
}
