package app.torrentmanager.monitoring;

import java.util.Optional;

public interface DownloadMonitorStateStore {
    Optional<DownloadMonitorState> load();

    void save(DownloadMonitorState state);
}
