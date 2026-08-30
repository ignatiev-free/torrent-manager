package app.torrentmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;

@ConfigurationProperties("daily-report")
public record DailyReportProperties(
        boolean enabled,
        LocalTime time,
        Duration pollInterval,
        Duration initialDelay,
        Path stateFile,
        Path actionHistoryFile,
        int maxActionHistoryEntries,
        int maxCurrentItems,
        int maxAttentionItems,
        boolean manualEnabled,
        String manualToken,
        Duration manualCooldown
) {
    public DailyReportProperties {
        if (time == null || pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("Время и интервал daily-report должны быть заданы");
        }
        if (initialDelay == null || initialDelay.isNegative() || stateFile == null
                || actionHistoryFile == null) {
            throw new IllegalArgumentException("Некорректная конфигурация хранилища daily-report");
        }
        if (maxActionHistoryEntries < 100 || maxCurrentItems < 1 || maxAttentionItems < 1) {
            throw new IllegalArgumentException("Лимиты daily-report должны быть положительными");
        }
        if (manualCooldown == null || manualCooldown.isNegative()) {
            throw new IllegalArgumentException("daily-report.manual-cooldown не может быть отрицательным");
        }
        if (enabled && manualEnabled && (manualToken == null || manualToken.length() < 16)) {
            throw new IllegalArgumentException(
                    "REPORTS_MANUAL_TOKEN должен содержать не менее 16 символов");
        }
    }
}
