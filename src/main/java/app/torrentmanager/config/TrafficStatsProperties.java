package app.torrentmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("traffic-stats")
public record TrafficStatsProperties(Path stateFile) {
}
