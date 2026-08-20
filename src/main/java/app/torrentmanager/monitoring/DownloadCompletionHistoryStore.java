package app.torrentmanager.monitoring;

import java.util.List;

public interface DownloadCompletionHistoryStore {
    List<DownloadCompletionRecord> load();

    void save(List<DownloadCompletionRecord> records);
}
