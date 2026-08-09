package app.torrentmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.nio.file.Path;

@ConfigurationProperties("torrent-manager")
public record ManagerProperties(
        boolean dryRun,
        Duration pollInterval,
        int maxActiveDownloads,
        int maxStopsPerCycle,
        Duration slowWindow,
        DataSize minimumDownloadSpeed,
        Duration limitedSlowWindow,
        double limitedThresholdRatio,
        DataSize minimumLimitedDownloadSpeed,
        double saturatedLimitRatio,
        Duration minimumActionInterval,
        Duration retryCooldown,
        Duration noSeedsTimeout,
        Path stateFile
) {
}
