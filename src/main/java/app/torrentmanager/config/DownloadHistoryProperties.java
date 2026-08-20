package app.torrentmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("download-history")
public record DownloadHistoryProperties(Path historyFile, int maxEntries) {
    public DownloadHistoryProperties {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("download-history.max-entries должен быть больше нуля");
        }
    }
}
