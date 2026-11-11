package app.torrentmanager.qbit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record QueuePreferences(
        @JsonProperty("queueing_enabled") boolean enabled,
        @JsonProperty("max_active_downloads") int downloads,
        @JsonProperty("max_active_uploads") int uploads,
        @JsonProperty("max_active_torrents") int total,
        @JsonProperty("dont_count_slow_torrents") boolean excludeSlow,
        @JsonProperty("dl_limit") long downloadLimit,
        @JsonProperty("up_limit") long uploadLimit,
        @JsonProperty("alt_dl_limit") long alternativeDownloadLimit,
        @JsonProperty("alt_up_limit") long alternativeUploadLimit,
        @JsonProperty("scheduler_enabled") boolean schedulerEnabled) {
    public void validate() {
        if (downloads < -1 || uploads < -1 || total < -1 || downloads > 10000 || uploads > 10000 || total > 10000)
            throw new IllegalArgumentException("Лимиты очереди: от 0 до 10000, -1 — без ограничения");
        if (downloadLimit < 0 || uploadLimit < 0 || alternativeDownloadLimit < 0 || alternativeUploadLimit < 0
                || downloadLimit > Integer.MAX_VALUE || uploadLimit > Integer.MAX_VALUE
                || alternativeDownloadLimit > Integer.MAX_VALUE || alternativeUploadLimit > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Недопустимый лимит скорости");
    }
}
