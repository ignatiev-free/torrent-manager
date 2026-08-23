package app.torrentmanager.report;

public record ReportStatusResponse(
        String lastDailyReport,
        String lastManualReport,
        String lastDeliveryStatus,
        String lastError
) {
}
