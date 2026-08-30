package app.torrentmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties("storage-report")
public record StorageReportProperties(boolean enabled, List<Disk> disks) {
    public StorageReportProperties {
        disks = disks == null ? List.of() : List.copyOf(disks);
    }

    public record Disk(String name, Path mountPath, String qbitPath) { }
}
