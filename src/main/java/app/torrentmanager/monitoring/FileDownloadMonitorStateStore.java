package app.torrentmanager.monitoring;

import app.torrentmanager.config.DownloadMonitorProperties;
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
import java.util.Optional;
import java.util.Properties;

@Component
public class FileDownloadMonitorStateStore implements DownloadMonitorStateStore {
    private static final Logger log = LoggerFactory.getLogger(FileDownloadMonitorStateStore.class);
    private static final String TORRENT_PREFIX = "torrent.";
    private static final String COMPLETE_SUFFIX = ".complete";
    private static final String NAME_SUFFIX = ".name";
    private static final String ADDED_AT_SUFFIX = ".addedAt";
    private static final String LAST_OBSERVED_AT_SUFFIX = ".lastObservedAt";
    private static final String ACTIVE_SECONDS_SUFFIX = ".activeSeconds";

    private final Path stateFile;

    public FileDownloadMonitorStateStore(DownloadMonitorProperties properties) {
        stateFile = properties.stateFile().toAbsolutePath().normalize();
    }

    @Override
    public synchronized Optional<DownloadMonitorState> load() {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(stateFile)) {
            properties.load(input);
            Instant initializedAt = Instant.parse(required(properties, "initializedAt"));
            Map<String, MonitoredTorrent> torrents = new LinkedHashMap<>();
            properties.stringPropertyNames().stream()
                    .filter(key -> key.startsWith(TORRENT_PREFIX) && key.endsWith(COMPLETE_SUFFIX))
                    .sorted()
                    .forEach(key -> {
                        String hash = key.substring(TORRENT_PREFIX.length(), key.length() - COMPLETE_SUFFIX.length());
                        boolean complete = Boolean.parseBoolean(properties.getProperty(key));
                        String name = required(properties, TORRENT_PREFIX + hash + NAME_SUFFIX);
                        torrents.put(hash, new MonitoredTorrent(name, complete,
                                instant(properties.getProperty(TORRENT_PREFIX + hash + ADDED_AT_SUFFIX)),
                                instant(properties.getProperty(TORRENT_PREFIX + hash + LAST_OBSERVED_AT_SUFFIX)),
                                longValue(properties.getProperty(TORRENT_PREFIX + hash + ACTIVE_SECONDS_SUFFIX))));
                    });
            log.info("Восстановлен мониторинг загрузок: {} торрентов", torrents.size());
            return Optional.of(new DownloadMonitorState(initializedAt, torrents));
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Не удалось прочитать состояние мониторинга: " + stateFile, exception);
        }
    }

    @Override
    public synchronized void save(DownloadMonitorState state) {
        Properties properties = new Properties();
        properties.setProperty("initializedAt", state.initializedAt().toString());
        state.torrents().forEach((hash, torrent) -> {
            properties.setProperty(TORRENT_PREFIX + hash + NAME_SUFFIX, torrent.name());
            properties.setProperty(TORRENT_PREFIX + hash + COMPLETE_SUFFIX, Boolean.toString(torrent.complete()));
            if (torrent.addedAt() != null) {
                properties.setProperty(TORRENT_PREFIX + hash + ADDED_AT_SUFFIX, torrent.addedAt().toString());
            }
            if (torrent.lastObservedAt() != null) {
                properties.setProperty(TORRENT_PREFIX + hash + LAST_OBSERVED_AT_SUFFIX,
                        torrent.lastObservedAt().toString());
            }
            properties.setProperty(TORRENT_PREFIX + hash + ACTIVE_SECONDS_SUFFIX,
                    Long.toString(torrent.activeSeconds()));
        });
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(stateFile.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "download-monitor state");
            }
            try {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось сохранить состояние мониторинга: " + stateFile, exception);
        }
    }

    private String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("В состоянии мониторинга отсутствует поле " + key);
        }
        return value;
    }

    private Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private long longValue(String value) {
        return value == null || value.isBlank() ? 0 : Long.parseLong(value);
    }
}
