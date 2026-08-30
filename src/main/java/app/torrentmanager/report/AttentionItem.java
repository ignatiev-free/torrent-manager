package app.torrentmanager.report;

import java.time.Instant;

public record AttentionItem(String hash, String name, double progress, String reason,
                            Instant since, int priority) {
}
