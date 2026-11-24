package app.torrentmanager.notification;

import app.torrentmanager.settings.RuntimeSettingsService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TelegramClient {
    private static final Set<String> REPORT_HEADINGS = Set.of("Готовые загрузки", "Требуют внимания", "Место на дисках");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final TelegramMode mode;
    private final RuntimeSettingsService settings;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public TelegramClient(RuntimeSettingsService settings, ObjectMapper json, TelegramMode mode) {
        this.mode = mode;
        this.settings = settings;
        this.json = json;
    }

    void send(String text, Duration timeout) {
        send(settings.valuesSnapshot(), text, List.of(), timeout);
    }

    void send(Map<String, String> config, String text, List<List<Button>> buttons, Duration timeout) {
        publish(config, text, buttons, timeout, null);
    }

    void edit(Map<String, String> config, String text, List<List<Button>> buttons, Duration timeout, long messageId) {
        publish(config, text, buttons, timeout, messageId);
    }

    private void publish(Map<String, String> config, String text, List<List<Button>> buttons,
                         Duration timeout, Long messageId) {
        if (text.length() > 4096) {
            int end = Character.isHighSurrogate(text.charAt(4093)) ? 4093 : 4094;
            text = text.substring(0, end) + "\n…";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", config.get("telegram-notifications.chat-id"));
        body.put("text", text);
        body.put("entities", boldHeadings(text));
        body.put("link_preview_options", Map.of("is_disabled", true));
        if (!buttons.isEmpty()) {
            body.put("reply_markup", Map.of("inline_keyboard", buttons.stream()
                    .map(row -> row.stream().map(b -> Map.of("text", b.label(), "callback_data", b.action())).toList())
                    .toList()));
        }
        if (messageId != null) body.put("message_id", messageId);
        call(config, messageId == null ? "sendMessage" : "editMessageText", body, timeout);
    }

    JsonNode updates(Map<String, String> config, long offset) {
        return call(config, "getUpdates", Map.of("offset", offset, "timeout", 20,
                "allowed_updates", List.of("message", "callback_query")), Duration.ofSeconds(27));
    }

    void acknowledge(Map<String, String> config, String id) {
        call(config, "answerCallbackQuery", Map.of("callback_query_id", id), REQUEST_TIMEOUT);
    }

    private JsonNode call(Map<String, String> config, String method, Map<String, ?> body, Duration timeout) {
        if (mode.integrated()) throw new TelegramDeliveryException("Telegram disabled in integrated mode");
        String token = config.get("telegram-notifications.bot-token");
        String chat = config.get("telegram-notifications.chat-id");
        if (token == null || !token.matches("[0-9]+:[A-Za-z0-9_-]+") || chat == null || chat.isBlank()) {
            throw new TelegramDeliveryException("Укажите токен бота и ID чата Telegram");
        }
        try {
            var request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/" + method))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            var payload = json.readTree(response.body());
            if (method.equals("editMessageText") && response.statusCode() == 400
                    && payload.path("description").asText().startsWith("Bad Request: message is not modified")) {
                return payload.path("result");
            }
            if (response.statusCode() != 200 || !payload.path("ok").asBoolean()) {
                throw new TelegramDeliveryException("Telegram отклонил " + method + " (HTTP " + response.statusCode() + ")");
            }
            return payload.path("result");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TelegramDeliveryException("Запрос Telegram прерван");
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new TelegramDeliveryException("Истекло время ожидания Telegram (" + method + ")");
        } catch (IOException | tools.jackson.core.JacksonException exception) {
            throw new TelegramDeliveryException("Ошибка ответа Telegram (" + method + "): "
                    + exception.getClass().getSimpleName()
                    + (exception.getCause() == null ? "" : " / " + exception.getCause().getClass().getSimpleName()));
        }
    }

    private static List<Map<String, Object>> boldHeadings(String text) {
        var entities = new ArrayList<Map<String, Object>>();
        int offset = 0;
        boolean report = text.startsWith("Ежедневный отчёт\n") || text.startsWith("Внеплановый отчёт\n");
        boolean current = text.startsWith("⬇️ В загрузке сейчас\n");
        boolean namedRows = text.startsWith("✅ Загрузка завершена\n")
                || text.startsWith("✅ Загрузки за 24 часа\n") || text.startsWith("💾 Место на дисках\n");
        for (String line : text.split("\n")) {
            if (!line.isEmpty() && (offset == 0 || (report && REPORT_HEADINGS.contains(line))
                    || ((report || current || namedRows) && line.startsWith("• "))
                    || (current && Set.of("Качаются", "Активны без передачи", "Пауза менеджера").contains(line)))) {
                entities.add(Map.of("type", "bold", "offset", offset, "length", line.length()));
            } else if (!line.isEmpty() && "▓░".indexOf(line.charAt(0)) >= 0) {
                entities.add(Map.of("type", "code", "offset", offset, "length", line.length()));
            }
            offset += line.length() + 1;
        }
        return entities;
    }

    public void test() {
        send("Torrent Manager — тестовое уведомление", REQUEST_TIMEOUT);
    }

    record Button(String label, String action) { }

    public static class TelegramDeliveryException extends RuntimeException {
        TelegramDeliveryException(String message) { super(message); }
    }
}
