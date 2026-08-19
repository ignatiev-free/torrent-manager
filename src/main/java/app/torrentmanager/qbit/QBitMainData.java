package app.torrentmanager.qbit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record QBitMainData(@JsonProperty("server_state") ServerState serverState) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ServerState(
            @JsonProperty("alltime_dl") long downloadedBytes,
            @JsonProperty("alltime_ul") long uploadedBytes
    ) {
    }
}
