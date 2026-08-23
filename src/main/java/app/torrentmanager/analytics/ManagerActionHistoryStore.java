package app.torrentmanager.analytics;

import java.util.List;

public interface ManagerActionHistoryStore {
    List<ManagerActionEvent> load();

    void save(List<ManagerActionEvent> events);
}
