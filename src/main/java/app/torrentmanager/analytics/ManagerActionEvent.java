package app.torrentmanager.analytics;

import java.time.Instant;

public record ManagerActionEvent(
        String hash,
        String name,
        ManagerActionType type,
        Instant at,
        double progress
) {
}
