package app.torrentmanager.report;

import app.torrentmanager.analytics.ManagerActionEvent;
import app.torrentmanager.analytics.ManagerActionType;
import app.torrentmanager.analytics.TorrentAnalyticsRecord;
import app.torrentmanager.config.DailyReportProperties;
import app.torrentmanager.monitoring.DownloadCompletionRecord;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class DailyReportFormatter {
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private final DailyReportProperties properties;
    private final TorrentAttentionAnalyzer attentionAnalyzer;

    public DailyReportFormatter(DailyReportProperties properties,
                                TorrentAttentionAnalyzer attentionAnalyzer) {
        this.properties = properties;
        this.attentionAnalyzer = attentionAnalyzer;
    }

    public String format(Instant from, Instant to, List<DownloadCompletionRecord> completions,
                         List<TorrentAnalyticsRecord> analytics,
                         List<ManagerActionEvent> actions, Map<String, Double> baseline) {
        ZoneId zone = ZoneId.systemDefault();
        List<TorrentAnalyticsRecord> current = analytics.stream()
                .filter(TorrentAnalyticsRecord::activeOrQueued)
                .sorted(Comparator.comparing((TorrentAnalyticsRecord record) ->
                                !isActive(record.state()))
                        .thenComparing(Comparator.comparingDouble(
                                TorrentAnalyticsRecord::progress).reversed()))
                .toList();
        List<AttentionItem> attention = attentionAnalyzer.analyze(analytics, to);
        long stopped = actions.stream().filter(event -> event.type() == ManagerActionType.STOPPED).count();
        long fair = actions.stream().filter(event -> event.type() == ManagerActionType.FAIR_ROTATION).count();
        long resumed = actions.stream().filter(event -> event.type() == ManagerActionType.RESUMED
                || event.type() == ManagerActionType.FAIR_ROTATION).count();

        StringBuilder report = new StringBuilder();
        report.append("Torrent Manager — отчёт за ")
                .append(DATE_TIME.format(from.atZone(zone))).append("–")
                .append(DATE_TIME.format(to.atZone(zone))).append("\n\n");
        appendCompleted(report, completions);
        appendCurrent(report, current, baseline);
        report.append("Действия менеджера:\n\n")
                .append("Остановлено: ").append(stopped).append('\n')
                .append("Возобновлено всего: ").append(resumed).append('\n')
                .append("Из них через справедливую ротацию: ").append(fair).append("\n\n");
        appendAttention(report, attention, to);
        return report.toString().stripTrailing();
    }

    public String formatHtml(Instant from, Instant to,
                             List<DownloadCompletionRecord> completions,
                             List<TorrentAnalyticsRecord> analytics,
                             List<ManagerActionEvent> actions, Map<String, Double> baseline) {
        ZoneId zone = ZoneId.systemDefault();
        List<TorrentAnalyticsRecord> current = current(analytics);
        List<AttentionItem> attention = attentionAnalyzer.analyze(analytics, to);
        long stopped = count(actions, ManagerActionType.STOPPED);
        long fair = count(actions, ManagerActionType.FAIR_ROTATION);
        long resumed = actions.stream().filter(event -> event.type() == ManagerActionType.RESUMED
                || event.type() == ManagerActionType.FAIR_ROTATION).count();
        String period = DATE_TIME.format(from.atZone(zone)) + "–" + DATE_TIME.format(to.atZone(zone));

        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="ru"><body style="margin:0;background:#f3f4f6;color:#111827;font-family:Arial,sans-serif">
                <div style="max-width:680px;margin:0 auto;padding:20px 12px">
                <div style="background:#172033;color:#fff;border-radius:14px;padding:22px">
                <div style="font-size:22px;font-weight:700">Torrent Manager</div>
                <div style="margin-top:6px;color:#cbd5e1;font-size:14px">""");
        html.append(escape(period)).append("</div></div>");
        html.append("<div style=\"display:flex;gap:8px;margin:12px 0;flex-wrap:wrap\">")
                .append(metric("Завершено", completions.size(), "#166534"))
                .append(metric("В загрузке", current.size(), "#1d4ed8"))
                .append(metric("Требуют внимания", attention.size(), attention.isEmpty() ? "#166534" : "#b45309"))
                .append("</div>");

        sectionStart(html, "Завершено за период", completions.size());
        if (completions.isEmpty()) {
            empty(html, "Нет завершённых загрузок");
        } else {
            int index = 1;
            for (DownloadCompletionRecord completion : completions) {
                row(html, index++ + ". " + completion.name(), null, "#166534");
            }
        }
        sectionEnd(html);

        sectionStart(html, "Сейчас в загрузке или очереди", current.size());
        int currentLimit = Math.min(current.size(), properties.maxCurrentItems());
        for (int index = 0; index < currentLimit; index++) {
            TorrentAnalyticsRecord record = current.get(index);
            Double previous = baseline.get(record.hash());
            String delta = previous == null ? ""
                    : " · за период +" + percent(Math.max(0, record.progress() - previous));
            String details = percent(record.progress()) + " · " + stateLabel(record.state())
                    + " · " + speed(record.downloadSpeed()) + delta
                    + "<br>Активно: " + duration(Duration.ofSeconds(record.activeSeconds()))
                    + " · очередь: " + duration(Duration.ofSeconds(record.queuedSeconds()));
            row(html, (index + 1) + ". " + record.name(), details, "#2563eb");
        }
        more(html, current.size() - currentLimit, "загрузок");
        sectionEnd(html);

        sectionStart(html, "Действия менеджера", null);
        html.append("<div style=\"padding:14px 16px;line-height:1.8\">")
                .append("Остановлено: <b>").append(stopped).append("</b><br>")
                .append("Возобновлено: <b>").append(resumed).append("</b><br>")
                .append("Справедливая ротация: <b>").append(fair).append("</b></div>");
        sectionEnd(html);

        sectionStart(html, "Требуют внимания", attention.size());
        if (attention.isEmpty()) {
            empty(html, "Подозрительных торрентов нет");
        } else {
            int attentionLimit = Math.min(attention.size(), properties.maxAttentionItems());
            for (int index = 0; index < attentionLimit; index++) {
                AttentionItem item = attention.get(index);
                String details = percent(item.progress()) + " · " + escape(item.reason())
                        + " · " + duration(Duration.between(item.since(), to));
                row(html, (index + 1) + ". " + item.name(), details, "#d97706");
            }
            more(html, attention.size() - attentionLimit, "торрентов");
        }
        sectionEnd(html);
        html.append("<div style=\"color:#6b7280;font-size:12px;text-align:center;padding:8px\">Причины определяются по наблюдаемым признакам и являются диагностической оценкой.</div>")
                .append("</div></body></html>");
        return html.toString();
    }

    private void appendCompleted(StringBuilder report, List<DownloadCompletionRecord> completions) {
        report.append("За период завершено: ").append(completions.size()).append("\n\n");
        if (completions.isEmpty()) {
            report.append("Нет завершённых загрузок.\n\n");
            return;
        }
        for (int index = 0; index < completions.size(); index++) {
            report.append(index + 1).append(". ").append(completions.get(index).name()).append('\n');
        }
        report.append('\n');
    }

    private void appendCurrent(StringBuilder report, List<TorrentAnalyticsRecord> current,
                               Map<String, Double> baseline) {
        report.append("На данный момент в загрузке или очереди: ")
                .append(current.size()).append("\n\n");
        int limit = Math.min(current.size(), properties.maxCurrentItems());
        for (int index = 0; index < limit; index++) {
            TorrentAnalyticsRecord record = current.get(index);
            Double previous = baseline.get(record.hash());
            String delta = previous == null ? ""
                    : ", за период +" + percent(Math.max(0, record.progress() - previous));
            report.append(index + 1).append(". ").append(record.name())
                    .append(" — ").append(percent(record.progress()))
                    .append(", ").append(stateLabel(record.state()))
                    .append(", ").append(speed(record.downloadSpeed()))
                    .append(", активно ").append(duration(Duration.ofSeconds(record.activeSeconds())))
                    .append(", в очереди ").append(duration(Duration.ofSeconds(record.queuedSeconds())))
                    .append(delta).append('\n');
        }
        if (current.size() > limit) {
            report.append("...и ещё ").append(current.size() - limit).append(" загрузок\n");
        }
        report.append('\n');
    }

    private List<TorrentAnalyticsRecord> current(List<TorrentAnalyticsRecord> analytics) {
        return analytics.stream().filter(TorrentAnalyticsRecord::activeOrQueued)
                .sorted(Comparator.comparing((TorrentAnalyticsRecord record) ->
                                !isActive(record.state()))
                        .thenComparing(Comparator.comparingDouble(
                                TorrentAnalyticsRecord::progress).reversed()))
                .toList();
    }

    private long count(List<ManagerActionEvent> actions, ManagerActionType type) {
        return actions.stream().filter(event -> event.type() == type).count();
    }

    private String metric(String label, long value, String color) {
        return "<div style=\"flex:1;min-width:130px;background:#fff;border-radius:10px;padding:13px;border-top:3px solid "
                + color + "\"><div style=\"font-size:22px;font-weight:700\">" + value
                + "</div><div style=\"color:#6b7280;font-size:12px\">" + escape(label) + "</div></div>";
    }

    private void sectionStart(StringBuilder html, String title, Integer count) {
        html.append("<div style=\"background:#fff;border-radius:12px;margin:12px 0;overflow:hidden\">")
                .append("<div style=\"padding:15px 16px;border-bottom:1px solid #e5e7eb;font-weight:700\">")
                .append(escape(title));
        if (count != null) html.append(" <span style=\"color:#6b7280\">(").append(count).append(")</span>");
        html.append("</div>");
    }

    private void sectionEnd(StringBuilder html) { html.append("</div>"); }

    private void empty(StringBuilder html, String text) {
        html.append("<div style=\"padding:16px;color:#6b7280\">").append(escape(text)).append("</div>");
    }

    private void row(StringBuilder html, String title, String details, String color) {
        html.append("<div style=\"padding:13px 16px;border-bottom:1px solid #f0f1f3;border-left:4px solid ")
                .append(color).append("\"><div style=\"font-weight:600;overflow-wrap:anywhere\">")
                .append(escape(title)).append("</div>");
        if (details != null) {
            html.append("<div style=\"margin-top:5px;color:#4b5563;font-size:13px;line-height:1.5\">")
                    .append(details).append("</div>");
        }
        html.append("</div>");
    }

    private void more(StringBuilder html, int count, String noun) {
        if (count > 0) empty(html, "И ещё " + count + " " + noun);
    }

    private String escape(String value) { return HtmlUtils.htmlEscape(value == null ? "" : value); }

    private void appendAttention(StringBuilder report, List<AttentionItem> attention, Instant now) {
        report.append("Торренты, на которые нужно обратить внимание: ")
                .append(attention.size()).append("\n\n");
        if (attention.isEmpty()) {
            report.append("Нет.\n");
            return;
        }
        int limit = Math.min(attention.size(), properties.maxAttentionItems());
        for (int index = 0; index < limit; index++) {
            AttentionItem item = attention.get(index);
            report.append(index + 1).append(". ").append(item.name())
                    .append(" — ").append(percent(item.progress())).append(", ")
                    .append(item.reason()).append(" уже ")
                    .append(duration(Duration.between(item.since(), now))).append('\n');
        }
        if (attention.size() > limit) {
            report.append("...и ещё ").append(attention.size() - limit).append(" торрентов\n");
        }
    }

    private boolean isActive(String state) {
        return !"queuedDL".equals(state) && !"stoppedDL".equals(state) && !"pausedDL".equals(state);
    }

    private String stateLabel(String state) {
        return switch (state) {
            case "queuedDL" -> "в очереди";
            case "forcedDL" -> "принудительная загрузка";
            case "stalledDL" -> "без передачи данных";
            case "checkingDL" -> "проверка файлов";
            case "metaDL" -> "получение метаданных";
            default -> "загружается";
        };
    }

    private String percent(double progress) {
        double value = progress * 100;
        return value >= 99 && value < 100 ? String.format("%.1f%%", value)
                : String.format("%.0f%%", value);
    }

    private String speed(long bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return "0 KiB/s";
        }
        if (bytesPerSecond >= 1024 * 1024) {
            return String.format("%.1f MiB/s", bytesPerSecond / 1024.0 / 1024.0);
        }
        return Math.round(bytesPerSecond / 1024.0) + " KiB/s";
    }

    private String duration(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return "0 мин.";
        }
        long days = Math.max(0, duration.toDays());
        if (days > 0) {
            return days + " дн.";
        }
        long hours = Math.max(0, duration.toHours());
        if (hours > 0) {
            return hours + " ч.";
        }
        return Math.max(1, duration.toMinutes()) + " мин.";
    }
}
