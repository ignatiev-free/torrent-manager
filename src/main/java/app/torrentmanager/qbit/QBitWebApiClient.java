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
    private final RestClient restClient;
    private final QBitProperties properties;
    private volatile String sessionCookie;

    public QBitWebApiClient(RestClient.Builder builder, QBitProperties properties) {
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.baseUrl().toString()).build();
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

    private <T> T authenticated(Request<T> request) {
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
