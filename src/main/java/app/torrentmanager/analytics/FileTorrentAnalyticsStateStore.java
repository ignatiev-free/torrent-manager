package app.torrentmanager.analytics;

import app.torrentmanager.config.TorrentAnalyticsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Component
public class FileTorrentAnalyticsStateStore implements TorrentAnalyticsStateStore {
    private static final Logger log = LoggerFactory.getLogger(FileTorrentAnalyticsStateStore.class);
    private final ObjectMapper objectMapper;
    private final Path stateFile;

    public FileTorrentAnalyticsStateStore(ObjectMapper objectMapper,
                                          TorrentAnalyticsProperties properties) {
        this.objectMapper = objectMapper;
        this.stateFile = properties.stateFile().toAbsolutePath().normalize();
    }

    @Override
    public synchronized Optional<TorrentAnalyticsState> load() {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        try {
            TorrentAnalyticsState state = objectMapper.readValue(
                    stateFile.toFile(), TorrentAnalyticsState.class);
            log.info("Восстановлена аналитика торрентов: {} записей", state.torrents().size());
            return Optional.of(state);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Не удалось прочитать аналитику: " + stateFile, exception);
        }
    }

    @Override
    public synchronized void save(TorrentAnalyticsState state) {
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(stateFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
            try {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось сохранить аналитику: " + stateFile, exception);
        }
    }
}
