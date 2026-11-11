package app.torrentmanager.settings;

import app.torrentmanager.config.ManagerProperties;
import app.torrentmanager.monitoring.*;
import app.torrentmanager.notification.DownloadNotificationService;
import app.torrentmanager.qbit.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "qbit.username=test", "qbit.password=integration-qbit-secret",
        "spring.mail.username=sender@example.com", "spring.mail.password=integration-mail-secret",
        "email-notifications.to=recipient@example.com", "email-notifications.enabled=false",
        "spring.mail.host=127.0.0.1", "spring.mail.port=2525",
        "traffic-stats.initial-delay=1h", "download-monitor.initial-delay=1h",
        "torrent-analytics.initial-delay=1h", "daily-report.initial-delay=1h"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminIntegrationTests {
    static final Path directory = temporaryDirectory();
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, Object> preferences = new LinkedHashMap<>(Map.of(
            "queueing_enabled", true, "max_active_downloads", 5, "max_active_uploads", 10,
            "max_active_torrents", 12, "dont_count_slow_torrents", false, "dl_limit", 0,
            "up_limit", 0, "alt_dl_limit", 1048576, "alt_up_limit", 524288, "scheduler_enabled", false));
    static volatile boolean alternativeMode;
    static volatile int mutations;
    static final HttpServer fixture = fixture();

    @LocalServerPort int port;
    @Autowired RuntimeSettingsService settings;
    @Autowired DownloadCompletionHistoryService history;
    @Autowired DownloadNotificationService delivery;
    @Autowired QBitClient qbit;
    @Autowired org.springframework.context.ApplicationContext context;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("qbit.base-url", () -> "http://127.0.0.1:" + fixture.getAddress().getPort());
        registry.add("web.settings-file", () -> directory.resolve("settings.properties").toString());
        registry.add("download-history.history-file", () -> directory.resolve("completions.json").toString());
        registry.add("daily-report.state-file", () -> directory.resolve("report.json").toString());
        registry.add("daily-report.action-history-file", () -> directory.resolve("actions.json").toString());
        registry.add("torrent-analytics.state-file", () -> directory.resolve("analytics.json").toString());
        registry.add("torrent-manager.state-file", () -> directory.resolve("manager.properties").toString());
    }

    @Test @Order(1) void opensWithoutLoginButRejectsForeignBrowserCommandsAndNeverReturnsSecrets() throws Exception {
        assertThat(request("GET", "/settings", null, false).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/manager/pause", null, false).statusCode()).isEqualTo(403);
        var foreign = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/manager/pause"))
                .header("Origin", "https://unrelated.example")
                .header("X-Torrent-Manager", "1").POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(HttpClient.newHttpClient().send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        var local = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/manager/resume"))
                .header("Origin", "http://127.0.0.1:" + port)
                .header("X-Torrent-Manager", "1").POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(HttpClient.newHttpClient().send(local, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        var response = request("GET", "/settings", null, true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).contains("qbit.password").doesNotContain("integration-qbit-secret", "integration-mail-secret");
    }

    @Test @Order(2) void savesLiveSettingsAndRejectsStaleOrInvalidWritesWithoutChangingState() throws Exception {
        long originalRevision = settings.revision();
        var response = request("PUT", "/settings", new RuntimeSettingsService.Update(originalRevision,
                Map.of("torrent-manager.retry-cooldown", "45m", "qbit.password", ""), null), true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(settings.current(ManagerProperties.class).retryCooldown()).isEqualTo(java.time.Duration.ofMinutes(45));
        assertThat(settings.value("qbit.password")).isEqualTo("integration-qbit-secret");
        String persisted = Files.readString(directory.resolve("settings.properties"));
        assertThat(persisted).contains("45m");
        assertThat(request("PUT", "/settings", new RuntimeSettingsService.Update(originalRevision,
                Map.of("torrent-manager.retry-cooldown", "1m"), null), true).statusCode()).isEqualTo(409);
        assertThat(request("PUT", "/settings", new RuntimeSettingsService.Update(settings.revision(),
                Map.of("torrent-manager.retry-cooldown", "-1m"), null), true).statusCode()).isEqualTo(400);
        assertThat(Files.readString(directory.resolve("settings.properties"))).isEqualTo(persisted);
        assertThat(request("PUT", "/settings", new RuntimeSettingsService.Update(settings.revision(),
                Map.of("spring.mail.properties.mail.smtp.starttls.enable", "true"), null), true).statusCode()).isEqualTo(400);
        assertThat(request("PUT", "/settings", new RuntimeSettingsService.Update(settings.revision(),
                Map.of("server.port", "1234"), null), true).statusCode()).isEqualTo(400);
    }

    @Test @Order(3) void enablesInitiallyDisabledNotificationsAndDoesNotReplayMutedCompletions() throws Exception {
        assertThat(request("POST", "/notifications/resume", null, true).statusCode()).isEqualTo(200);
        assertThat(settings.notificationsAllowed()).isTrue();
        history.record(new DownloadCompletedEvent("first", "First", Instant.now()));
        assertThat(history.recent(1).getFirst().deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(request("POST", "/notifications/hour", null, true).statusCode()).isEqualTo(200);
        assertThat(settings.notificationsAllowed()).isFalse();
        assertThat(history.recent(1).getFirst().deliveryStatus()).isEqualTo(DeliveryStatus.SUPPRESSED);
        history.record(new DownloadCompletedEvent("second", "Second", Instant.now()));
        assertThat(history.recent(1).getFirst().deliveryStatus()).isEqualTo(DeliveryStatus.NOT_CONFIGURED);
        assertThat(request("POST", "/notifications/resume", null, true).statusCode()).isEqualTo(200);
        assertThat(history.nextPending(Instant.now())).isEmpty();
        request("POST", "/notifications/off", null, true);
    }

    @Test @Order(4) void roundTripsQueueAndSpeedAndDetectsChangesMadeDirectlyInQbit() throws Exception {
        var original = JSON.readValue(request("GET", "/queue", null, true).body(), AdminController.QueueState.class);
        var desired = new AdminController.QueueState(new QueuePreferences(true, 7, 8, 13, false,
                3 * 1024 * 1024, 1024 * 1024, 512 * 1024, 256 * 1024, false), true);
        var response = request("PUT", "/queue", new AdminController.QueueUpdate(original, desired), true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(qbit.getQueuePreferences().downloadLimit()).isEqualTo(3 * 1024 * 1024);
        assertThat(qbit.alternativeSpeedMode()).isTrue();
        synchronized (preferences) { preferences.put("dl_limit", 123456); }
        int before = mutations;
        assertThat(request("PUT", "/queue", new AdminController.QueueUpdate(desired, original), true).statusCode()).isEqualTo(409);
        assertThat(mutations).isEqualTo(before);
    }

    @Test @Order(5) void configuresArbitraryDiskNamesAndClearsTheDiskList() throws Exception {
        var disks = List.of(new app.torrentmanager.config.StorageReportProperties.Disk("Медиатека · 18 ТБ", directory,
                List.of("/downloads18", "/media18")));
        assertThat(request("PUT", "/settings", new RuntimeSettingsService.Update(settings.revision(),
                Map.of("storage-report.enabled", "true"), disks), true).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/storage", null, true).body()).contains("Медиатека · 18 ТБ").doesNotContain("HDD1");
        var view = JSON.readValue(request("GET", "/settings", null, true).body(), RuntimeSettingsService.View.class);
        assertThat(view.disks().getFirst().mountPath()).isEqualTo(directory.toString());
        assertThat(request("PUT", "/settings", new RuntimeSettingsService.Update(settings.revision(),
                Map.of("storage-report.enabled", "false"), List.of()), true).statusCode()).isEqualTo(200);
        assertThat(settings.current(app.torrentmanager.config.StorageReportProperties.class).disks()).isEmpty();
    }

    @Test @Order(6) void reloadsPersistedSettingsAndKeepsOldSnapshotWhenStorageWriteFails() throws Exception {
        Path file = directory.resolve("restart/settings.properties");
        RuntimeSettingsService first = isolatedSettings(file);
        first.save(new RuntimeSettingsService.Update(first.revision(), Map.of(
                "torrent-manager.retry-cooldown", "37m", "qbit.password", "new-secret-with-spaces "), null));
        RuntimeSettingsService restarted = isolatedSettings(file);
        assertThat(restarted.current(ManagerProperties.class).retryCooldown()).isEqualTo(java.time.Duration.ofMinutes(37));
        assertThat(restarted.value("qbit.password")).isEqualTo("new-secret-with-spaces ");
        assertThat(restarted.view().values().get("qbit.password")).isEmpty();
        assertThat(restarted.view().values().get("torrent-manager.retry-cooldown")).isEqualTo("37m");
        Path blockedParent = directory.resolve("cannot-create-directory");
        RuntimeSettingsService blocked = isolatedSettings(blockedParent.resolve("settings.properties"));
        Files.writeString(blockedParent, "This file prevents directory creation");
        long revision = blocked.revision();
        assertThatThrownBy(() -> blocked.save(new RuntimeSettingsService.Update(revision,
                Map.of("torrent-manager.retry-cooldown", "1h"), null))).isInstanceOf(IllegalStateException.class);
        assertThat(blocked.revision()).isEqualTo(revision);
        assertThat(blocked.current(ManagerProperties.class).retryCooldown()).isEqualTo(java.time.Duration.ofMinutes(20));
    }

    @Test @Order(7) void applicationHealthDoesNotDependOnDisabledUnconfiguredSmtp() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health")).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test @Order(8) void returnsExplicitCooldownAndRetryAfterWithoutSendingMail() throws Exception {
        var reports = context.getBean(app.torrentmanager.report.DailyReportService.class);
        Object original = org.springframework.test.util.ReflectionTestUtils.getField(reports, "state");
        try {
            settings.mute("resume");
            org.springframework.test.util.ReflectionTestUtils.setField(reports, "state",
                    new app.torrentmanager.report.DailyReportState(null, null, Instant.now(), "DELIVERED", null, Map.of()));
            var response = request("POST", "/reports/send", null, true);
            assertThat(response.statusCode()).isEqualTo(429);
            assertThat(response.headers().firstValue("Retry-After")).isPresent();
            assertThat(response.body()).contains("Повторная отправка доступна", "nextAllowedAt").doesNotContain("Действие недоступно");
            assertThat(reports.status().lastDeliveryStatus()).isEqualTo("DELIVERED");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(reports, "state", original);
            settings.mute("off");
        }
    }

    @Test @Order(9) void reportPageAndStatusAreReadOnlyAndNeedNoToken() throws Exception {
        var reports = context.getBean(app.torrentmanager.report.DailyReportService.class);
        var before = reports.status();
        var page = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/reports?embed=1")).GET().build();
        var response = HttpClient.newHttpClient().send(page, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("/reports.js", "id=\"send-report\"").doesNotContain("torrentReportToken", "prompt(");
        assertThat(request("GET", "/reports/status", null, false).statusCode()).isEqualTo(200);
        assertThat(reports.status()).isEqualTo(before);
    }

    private RuntimeSettingsService isolatedSettings(Path file) {
        var environment = new org.springframework.mock.env.MockEnvironment();
        environment.setProperty("web.settings-file", file.toString());
        settings.valuesSnapshot().forEach((key, value) -> { if (key.startsWith("spring.mail.")) environment.setProperty(key, value); });
        return new RuntimeSettingsService(context.getBean(ManagerProperties.class),
                context.getBean(app.torrentmanager.config.EmailNotificationProperties.class),
                context.getBean(app.torrentmanager.config.DownloadStallAlertProperties.class),
                context.getBean(app.torrentmanager.config.DailyReportProperties.class),
                context.getBean(app.torrentmanager.config.StorageReportProperties.class),
                context.getBean(app.torrentmanager.config.QBitProperties.class),
                context.getBean(app.torrentmanager.config.TorrentAnalyticsProperties.class), environment, event -> { });
    }

    private HttpResponse<String> request(String method, String path, Object body, boolean panelRequest) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin" + path))
                .header("Content-Type", "application/json");
        if (panelRequest) builder.header("X-Torrent-Manager", "1");
        return HttpClient.newHttpClient().send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    @AfterAll static void stop() { fixture.stop(0); }
    private static Path temporaryDirectory() {
        try { Files.createDirectories(Path.of("build")); return Files.createTempDirectory(Path.of("build"), "admin-it-").toAbsolutePath(); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static HttpServer fixture() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v2/", exchange -> {
                String path = exchange.getRequestURI().getPath(), body = "";
                if (path.endsWith("/login")) { exchange.getResponseHeaders().add("Set-Cookie", "SID=test-session; HttpOnly"); body = "Ok."; }
                else if (path.endsWith("/setPreferences")) {
                    var form = URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
                    synchronized (preferences) { preferences.putAll(JSON.readValue(form.substring(5), Map.class)); }
                    mutations++;
                } else if (path.endsWith("/preferences")) { synchronized (preferences) { body = JSON.writeValueAsString(preferences); } }
                else if (path.endsWith("/setSpeedLimitsMode")) { alternativeMode = new String(exchange.getRequestBody().readAllBytes()).contains("mode=1"); mutations++; }
                else if (path.endsWith("/speedLimitsMode")) body = alternativeMode ? "1" : "0";
                else if (path.endsWith("/torrents/info")) body = "[]";
                else if (path.endsWith("/transfer/info")) body = "{\"dl_info_speed\":0,\"dl_rate_limit\":0}";
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length == 0 ? -1 : bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start(); return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
}
