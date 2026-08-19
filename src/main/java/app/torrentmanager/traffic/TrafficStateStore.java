package app.torrentmanager.traffic;

import java.util.Optional;

public interface TrafficStateStore {
    Optional<TrafficState> load();

    void save(TrafficState state);
}
