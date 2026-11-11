package app.torrentmanager.qbit;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransferInfo(
        @JsonProperty("dl_info_speed") long downloadSpeed,
        @JsonProperty("dl_rate_limit") long downloadRateLimit,
        @JsonProperty("use_alt_speed_limits") Boolean alternativeSpeedLimitsEnabled,
        @JsonProperty("up_info_speed") long uploadSpeed,
        @JsonProperty("up_rate_limit") long uploadRateLimit
) {
    public TransferInfo(long downloadSpeed, long downloadRateLimit, Boolean alternativeSpeedLimitsEnabled) {
        this(downloadSpeed, downloadRateLimit, alternativeSpeedLimitsEnabled, 0, 0);
    }
    public boolean isDownloadLimited() {
        return downloadRateLimit > 0;
    }
}
