package app.torrentmanager.report;

import java.util.Optional;

public interface DailyReportStateStore {
    Optional<DailyReportState> load();

    void save(DailyReportState state);
}
