package app.torrentmanager.analytics;

import java.util.List;

public record ManagerActionHistory(List<ManagerActionEvent> events) {
    public ManagerActionHistory {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
