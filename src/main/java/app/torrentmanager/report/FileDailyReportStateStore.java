package app.torrentmanager.report;

import app.torrentmanager.config.DailyReportProperties;
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
public class FileDailyReportStateStore implements DailyReportStateStore {
    private static final Logger log = LoggerFactory.getLogger(FileDailyReportStateStore.class);
    private final ObjectMapper objectMapper;
    private final Path stateFile;

    public FileDailyReportStateStore(ObjectMapper objectMapper, DailyReportProperties properties) {
        this.objectMapper = objectMapper;
        this.stateFile = properties.stateFile().toAbsolutePath().normalize();
    }

    @Override
    public synchronized Optional<DailyReportState> load() {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        try {
            DailyReportState state = objectMapper.readValue(stateFile.toFile(), DailyReportState.class);
            log.info("Восстановлено состояние ежедневных отчётов: {}",
                    state.lastDailyReportAt() == null ? "отчёты ещё не отправлялись"
                            : state.lastDailyReportAt());
            return Optional.of(state);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Не удалось прочитать состояние отчётов: " + stateFile,
                    exception);
        }
    }

    @Override
    public synchronized void save(DailyReportState state) {
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
            throw new IllegalStateException("Не удалось сохранить состояние отчётов: " + stateFile,
                    exception);
        }
    }
}
