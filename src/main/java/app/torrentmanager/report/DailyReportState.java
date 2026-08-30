package app.torrentmanager.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public record DailyReportState(
        LocalDate lastDailyReportDate,
        Instant lastDailyReportAt,
        Instant lastManualReportAt,
        String lastDeliveryStatus,
        String lastError,
        Map<String, Double> progressBaseline
) {
    public DailyReportState {
        progressBaseline = progressBaseline == null
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(progressBaseline));
    }

    public static DailyReportState empty() {
        return new DailyReportState(null, null, null, "NEVER", null, Map.of());
    }
}
