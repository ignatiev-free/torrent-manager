package app.torrentmanager.qbit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Torrent(
        String hash,
        String name,
        String state,
        double progress,
        @JsonProperty("dlspeed") long downloadSpeed,
        @JsonProperty("num_seeds") int connectedSeeds,
        @JsonProperty("num_complete") int availableSeeds,
        int priority
) {
    public boolean isComplete() {
        return progress >= 1.0;
    }

    public boolean isActivelyDownloading() {
        return !isComplete() && switch (state) {
            case "downloading", "stalledDL", "metaDL", "forcedDL", "checkingDL", "allocating" -> true;
            default -> false;
        };
    }

    public boolean isForced() {
        return "forcedDL".equals(state);
    }

    public boolean isQueuedForDownload() {
        return !isComplete() && "queuedDL".equals(state);
    }
}
