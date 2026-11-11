package app.torrentmanager.qbit;

import app.torrentmanager.config.QBitProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Component
public class QBitWebApiClient implements QBitClient {
    private RestClient restClient;
    private QBitProperties properties;
    private final RestClient.Builder builder;
    @org.springframework.beans.factory.annotation.Autowired
    private app.torrentmanager.settings.RuntimeSettingsService runtimeSettings;
    private volatile String sessionCookie;

    public QBitWebApiClient(RestClient.Builder builder, QBitProperties properties) {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(10));
        this.builder = builder.clone().requestFactory(factory);
        this.properties = properties;
        this.restClient = this.builder.clone().baseUrl(properties.baseUrl().toString()).build();
    }

    @Override public QueuePreferences getQueuePreferences() {
        return authenticated(() -> restClient.get().uri("/api/v2/app/preferences")
                .header(HttpHeaders.COOKIE, sessionCookie).retrieve().body(QueuePreferences.class));
    }

    @Override public void setQueuePreferences(QueuePreferences p) {
        p.validate();
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("queueing_enabled", p.enabled());
        values.put("max_active_downloads", p.downloads());
        values.put("max_active_uploads", p.uploads());
        values.put("max_active_torrents", p.total());
        values.put("dont_count_slow_torrents", p.excludeSlow());
        values.put("dl_limit", p.downloadLimit());
        values.put("up_limit", p.uploadLimit());
        values.put("alt_dl_limit", p.alternativeDownloadLimit());
        values.put("alt_up_limit", p.alternativeUploadLimit());
        authenticated(() -> {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("json", new tools.jackson.databind.ObjectMapper().writeValueAsString(values));
            restClient.post().uri("/api/v2/app/setPreferences").header(HttpHeaders.COOKIE, sessionCookie)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();
            return null;
        });
    }

    @Override public boolean alternativeSpeedMode() {
        return authenticated(() -> "1".equals(restClient.get().uri("/api/v2/transfer/speedLimitsMode")
                .header(HttpHeaders.COOKIE, sessionCookie).retrieve().body(String.class)));
    }

    @Override public void setAlternativeSpeedMode(boolean enabled) {
        authenticated(() -> {
            if (alternativeSpeedMode() == enabled) return null;
            var form = new LinkedMultiValueMap<String, String>();
            form.add("mode", enabled ? "1" : "0");
            try {
                restClient.post().uri("/api/v2/transfer/setSpeedLimitsMode").header(HttpHeaders.COOKIE, sessionCookie)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();
            } catch (RestClientResponseException exception) {
                if (exception.getStatusCode().value() != 404) throw exception;
                // Older 5.x versions only expose a toggle; do not retry it blindly.
                if (alternativeSpeedMode() != enabled) restClient.post().uri("/api/v2/transfer/toggleSpeedLimitsMode")
                        .header(HttpHeaders.COOKIE, sessionCookie).retrieve().toBodilessEntity();
            }
            return null;
        });
    }

    @Override
    public List<Torrent> getTorrents() {
        return authenticated(() -> restClient.get()
                .uri("/api/v2/torrents/info")
                .header(HttpHeaders.COOKIE, sessionCookie)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {}));
    }

    @Override
    public TransferInfo getTransferInfo() {
        return authenticated(() -> restClient.get()
                .uri("/api/v2/transfer/info")
                .header(HttpHeaders.COOKIE, sessionCookie)
                .retrieve()
                .body(TransferInfo.class));
    }

    @Override
    public LifetimeTransferInfo getLifetimeTransferInfo() {
        QBitMainData mainData = authenticated(() -> restClient.get()
                .uri("/api/v2/sync/maindata?rid=0")
                .header(HttpHeaders.COOKIE, sessionCookie)
                .retrieve()
                .body(QBitMainData.class));
        if (mainData == null || mainData.serverState() == null) {
            throw new IllegalStateException("qBittorrent не вернул накопительную статистику");
        }
        return new LifetimeTransferInfo(
                mainData.serverState().downloadedBytes(),
                mainData.serverState().uploadedBytes());
    }

    @Override
    public void stop(String hash) {
        command("/api/v2/torrents/stop", hash);
    }

    @Override
    public void start(String hash) {
        command("/api/v2/torrents/start", hash);
    }

    @Override
    public void moveToTop(String hash) {
        command("/api/v2/torrents/topPrio", hash);
    }

    private void command(String endpoint, String hash) {
        authenticated(() -> {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("hashes", hash);
            restClient.post().uri(endpoint)
                    .header(HttpHeaders.COOKIE, sessionCookie)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().toBodilessEntity();
            return null;
        });
    }

    private synchronized void login() {
        if (properties.password() == null || properties.password().isBlank()) {
            throw new IllegalStateException("QBITTORRENT_PASSWORD не задан");
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("username", properties.username());
        form.add("password", properties.password());
        var response = restClient.post().uri("/api/v2/auth/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().toEntity(String.class);
        var cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null || cookies.isEmpty()) {
            throw new IllegalStateException("qBittorrent не вернул идентификатор сессии");
        }
        sessionCookie = cookies.getFirst().split(";", 2)[0];
    }

    private synchronized <T> T authenticated(Request<T> request) {
        QBitProperties current = runtimeSettings == null ? properties : runtimeSettings.current(QBitProperties.class);
        if (!current.equals(properties)) {
            properties = current;
            restClient = builder.clone().baseUrl(current.baseUrl().toString()).build();
            sessionCookie = null;
        }
        if (sessionCookie == null) {
            login();
        }
        try {
            return request.execute();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 403) {
                throw exception;
            }
            sessionCookie = null;
            login();
            return request.execute();
        }
    }

    @FunctionalInterface
    private interface Request<T> {
        T execute();
    }
}
