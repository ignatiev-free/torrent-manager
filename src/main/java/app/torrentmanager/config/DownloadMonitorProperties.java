package app.torrentmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("download-monitor")
public record DownloadMonitorProperties(Path stateFile) {
}
