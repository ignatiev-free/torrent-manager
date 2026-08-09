package app.torrentmanager.manager;

import java.time.Instant;
import java.util.Map;

public interface ManagerStateStore {
    Map<String, Instant> load();

    void save(Map<String, Instant> pausedTorrents);

    static ManagerStateStore noOp() {
        return new ManagerStateStore() {
            @Override
            public Map<String, Instant> load() {
                return Map.of();
            }

            @Override
            public void save(Map<String, Instant> pausedTorrents) {
            }
        };
    }
}
