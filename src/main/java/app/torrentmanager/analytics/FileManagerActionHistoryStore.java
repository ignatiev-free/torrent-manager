package app.torrentmanager.analytics;

import app.torrentmanager.config.DailyReportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Component
public class FileManagerActionHistoryStore implements ManagerActionHistoryStore {
    private static final Logger log = LoggerFactory.getLogger(FileManagerActionHistoryStore.class);
    private final ObjectMapper objectMapper;
    private final Path historyFile;

    public FileManagerActionHistoryStore(ObjectMapper objectMapper, DailyReportProperties properties) {
        this.objectMapper = objectMapper;
        this.historyFile = properties.actionHistoryFile().toAbsolutePath().normalize();
    }

    @Override
    public synchronized List<ManagerActionEvent> load() {
        if (!Files.exists(historyFile)) {
            return List.of();
        }
        try {
            ManagerActionHistory history = objectMapper.readValue(
                    historyFile.toFile(), ManagerActionHistory.class);
            log.info("Восстановлена история действий менеджера: {} событий", history.events().size());
            return history.events();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Не удалось прочитать историю действий: " + historyFile,
                    exception);
        }
    }

    @Override
    public synchronized void save(List<ManagerActionEvent> events) {
        Path temporary = historyFile.resolveSibling(historyFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(historyFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    temporary.toFile(), new ManagerActionHistory(events));
            try {
                Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось сохранить историю действий: " + historyFile,
                    exception);
        }
    }
}
