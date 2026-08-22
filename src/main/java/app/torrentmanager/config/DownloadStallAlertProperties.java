package app.torrentmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties("download-stall-alert")
public record DownloadStallAlertProperties(
        boolean enabled,
        Duration pollInterval,
        Duration initialDelay,
        DataSize zeroSpeedThreshold,
        Duration alertAfter,
        DataSize recoverySpeed,
        Duration recoveryWindow
) {
    public DownloadStallAlertProperties {
        requirePositive(pollInterval, "poll-interval");
        requireNonNegative(initialDelay, "initial-delay");
        requireNonNegative(alertAfter, "alert-after");
        requireNonNegative(recoveryWindow, "recovery-window");
        if (zeroSpeedThreshold == null || zeroSpeedThreshold.isNegative()) {
            throw new IllegalArgumentException("download-stall-alert.zero-speed-threshold не может быть отрицательным");
        }
        if (recoverySpeed == null || recoverySpeed.isNegative()) {
            throw new IllegalArgumentException("download-stall-alert.recovery-speed не может быть отрицательным");
        }
        if (recoverySpeed.toBytes() <= zeroSpeedThreshold.toBytes()) {
            throw new IllegalArgumentException(
                    "download-stall-alert.recovery-speed должен быть выше zero-speed-threshold");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("download-stall-alert." + name + " должен быть больше нуля");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException("download-stall-alert." + name + " не может быть отрицательным");
        }
    }
}
