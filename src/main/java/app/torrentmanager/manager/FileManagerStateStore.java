package app.torrentmanager.manager;

import app.torrentmanager.config.ManagerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

@Component
public class FileManagerStateStore implements ManagerStateStore {
    private static final Logger log = LoggerFactory.getLogger(FileManagerStateStore.class);
    private final Path stateFile;

    public FileManagerStateStore(ManagerProperties properties) {
        this.stateFile = properties.stateFile().toAbsolutePath().normalize();
    }

    @Override
    public synchronized Map<String, Instant> load() {
        if (!Files.exists(stateFile)) {
            return Map.of();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(stateFile)) {
            properties.load(input);
            Map<String, Instant> result = new LinkedHashMap<>();
            for (String hash : properties.stringPropertyNames()) {
                result.put(hash, Instant.ofEpochMilli(Long.parseLong(properties.getProperty(hash))));
            }
            log.info("Восстановлено приостановленных торрентов: {}", result.size());
            return result;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Не удалось прочитать состояние менеджера: " + stateFile, exception);
        }
    }

    @Override
    public synchronized void save(Map<String, Instant> pausedTorrents) {
        Properties properties = new Properties();
        pausedTorrents.forEach((hash, retryAt) -> properties.setProperty(hash, Long.toString(retryAt.toEpochMilli())));
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(stateFile.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "torrent-manager state; values are retry timestamps in epoch milliseconds");
            }
            try {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось сохранить состояние менеджера: " + stateFile, exception);
        }
    }
}
