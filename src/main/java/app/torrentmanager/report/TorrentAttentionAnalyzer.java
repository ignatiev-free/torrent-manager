package app.torrentmanager.report;

import app.torrentmanager.analytics.TorrentAnalyticsRecord;
import app.torrentmanager.config.TorrentAnalyticsProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class TorrentAttentionAnalyzer {
    private final TorrentAnalyticsProperties properties;

    public TorrentAttentionAnalyzer(TorrentAnalyticsProperties properties) {
        this.properties = properties;
    }

    public List<AttentionItem> analyze(List<TorrentAnalyticsRecord> records, Instant now) {
        List<AttentionItem> result = new ArrayList<>();
        for (TorrentAnalyticsRecord record : records) {
            AttentionItem item = classify(record, now);
            if (item != null) {
                result.add(item);
            }
        }
        result.sort(Comparator.comparingInt(AttentionItem::priority).reversed()
                .thenComparing(AttentionItem::since));
        return List.copyOf(result);
    }

    private AttentionItem classify(TorrentAnalyticsRecord record, Instant now) {
        if (record.progress() >= properties.nearCompleteProgress()
                && elapsed(record.lastProgressAt(), now,
                properties.nearCompleteStalledAfter().getSeconds())) {
            return item(record, "прогресс почти завершён и не меняется", record.lastProgressAt(), 5);
        }
        if (record.noSeedsSince() != null
                && elapsed(record.noSeedsSince(), now, properties.noSeedsAfter().getSeconds())) {
            return item(record, "нет доступных сидов", record.noSeedsSince(), 4);
        }
        if (record.forced() && record.downloadSpeed() <= properties.stalledSpeedThreshold().toBytes()
                && elapsed(record.lastProgressAt(), now, properties.forcedStalledAfter().getSeconds())) {
            return item(record, "принудительная загрузка без прогресса", record.lastProgressAt(), 4);
        }
        if (record.attemptsSinceProgress() >= properties.maxUnproductiveAttempts()) {
            return item(record, "повторные попытки без прироста: " + record.attemptsSinceProgress(),
                    record.lastProgressAt(), 3);
        }
        if (elapsed(record.lastProgressAt(), now, properties.noProgressAfter().getSeconds())) {
            return item(record, "прогресс не меняется", record.lastProgressAt(), 2);
        }
        if (record.progress() <= properties.oldTorrentProgress()
                && elapsed(record.addedAt(), now, properties.oldTorrentAfter().getSeconds())) {
            return item(record, "давно добавлен, прогресс остаётся низким", record.addedAt(), 1);
        }
        return null;
    }

    private AttentionItem item(TorrentAnalyticsRecord record, String reason, Instant since,
                               int priority) {
        return new AttentionItem(record.hash(), record.name(), record.progress(), reason,
                since, priority);
    }

    private boolean elapsed(Instant since, Instant now, long seconds) {
        return since != null && !since.plusSeconds(seconds).isAfter(now);
    }
}
