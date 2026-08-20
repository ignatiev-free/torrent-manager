package app.torrentmanager.monitoring;

import app.torrentmanager.config.DownloadHistoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Component
public class FileDownloadCompletionHistoryStore implements DownloadCompletionHistoryStore {
    private static final Logger log = LoggerFactory.getLogger(FileDownloadCompletionHistoryStore.class);
    private static final TypeReference<List<DownloadCompletionRecord>> HISTORY_TYPE = new TypeReference<>() { };

    private final ObjectMapper objectMapper;
    private final Path historyFile;

    public FileDownloadCompletionHistoryStore(ObjectMapper objectMapper, DownloadHistoryProperties properties) {
        this.objectMapper = objectMapper;
        this.historyFile = properties.historyFile().toAbsolutePath().normalize();
    }

    @Override
    public synchronized List<DownloadCompletionRecord> load() {
        if (!Files.exists(historyFile)) {
            return List.of();
        }
        try {
            List<DownloadCompletionRecord> records = objectMapper.readValue(historyFile.toFile(), HISTORY_TYPE);
            log.info("Восстановлена история завершённых загрузок: {} записей", records.size());
            return List.copyOf(records);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Не удалось прочитать историю загрузок: " + historyFile, exception);
        }
    }

    @Override
    public synchronized void save(List<DownloadCompletionRecord> records) {
        Path temporary = historyFile.resolveSibling(historyFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(historyFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), records);
            try {
                Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось сохранить историю загрузок: " + historyFile, exception);
        }
    }
}
