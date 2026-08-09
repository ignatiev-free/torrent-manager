package app.torrentmanager.qbit;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransferInfo(
        @JsonProperty("dl_info_speed") long downloadSpeed,
        @JsonProperty("dl_rate_limit") long downloadRateLimit,
        @JsonProperty("use_alt_speed_limits") Boolean alternativeSpeedLimitsEnabled
) {
    public boolean isDownloadLimited() {
        return downloadRateLimit > 0;
    }
}
