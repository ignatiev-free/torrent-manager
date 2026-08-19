package app.torrentmanager.traffic;

import app.torrentmanager.config.TrafficStatsProperties;
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
import java.time.LocalDate;
import java.util.Optional;
import java.util.Properties;

@Component
public class FileTrafficStateStore implements TrafficStateStore {
    private static final Logger log = LoggerFactory.getLogger(FileTrafficStateStore.class);
    private final Path stateFile;

    public FileTrafficStateStore(TrafficStatsProperties properties) {
        stateFile = properties.stateFile().toAbsolutePath().normalize();
    }

    @Override
    public synchronized Optional<TrafficState> load() {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(stateFile)) {
            properties.load(input);
            TrafficState state = new TrafficState(
                    LocalDate.parse(required(properties, "date")),
                    parseLong(properties, "lastDownloadedBytes"),
                    parseLong(properties, "lastUploadedBytes"),
                    parseLong(properties, "downloadedTodayBytes"),
                    parseLong(properties, "uploadedTodayBytes"),
                    Instant.parse(required(properties, "trackingSince")));
            log.info("Восстановлена статистика трафика qBittorrent за {}", state.date());
            return Optional.of(state);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Не удалось прочитать статистику трафика: " + stateFile, exception);
        }
    }

    @Override
    public synchronized void save(TrafficState state) {
        Properties properties = new Properties();
        properties.setProperty("date", state.date().toString());
        properties.setProperty("lastDownloadedBytes", Long.toString(state.lastDownloadedBytes()));
        properties.setProperty("lastUploadedBytes", Long.toString(state.lastUploadedBytes()));
        properties.setProperty("downloadedTodayBytes", Long.toString(state.downloadedTodayBytes()));
        properties.setProperty("uploadedTodayBytes", Long.toString(state.uploadedTodayBytes()));
        properties.setProperty("trackingSince", state.trackingSince().toString());

        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(stateFile.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "qBittorrent traffic statistics");
            }
            try {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось сохранить статистику трафика: " + stateFile, exception);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Отсутствует свойство " + key);
        }
        return value;
    }

    private static long parseLong(Properties properties, String key) {
        long value = Long.parseLong(required(properties, key));
        if (value < 0) {
            throw new IllegalArgumentException("Свойство " + key + " не может быть отрицательным");
        }
        return value;
    }
}
