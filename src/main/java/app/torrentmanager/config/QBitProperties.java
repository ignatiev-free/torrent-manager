package app.torrentmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties("qbit")
public record QBitProperties(URI baseUrl, String username, String password) {
}
