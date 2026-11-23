package app.torrentmanager.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class IntegrationOperations {
    private final Path directory;

    IntegrationOperations(@Value("${web.settings-file}") Path settings) {
        this.directory = settings.toAbsolutePath().resolveSibling("integration-operations");
    }

    synchronized boolean reserve(UUID id) {
        try {
            Files.createDirectories(directory);
            int count = 0;
            var cutoff = Instant.now().minus(Duration.ofDays(7));
            try (var files = Files.newDirectoryStream(directory, "*.op")) {
                for (Path path : files) {
                    if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) Files.deleteIfExists(path);
                    else count++;
                }
            }
            if (count >= 4096) throw new IllegalStateException("Operation capacity exhausted");
            Files.createFile(directory.resolve(id + ".op"));
            return true;
        } catch (FileAlreadyExistsException repeated) {
            return false;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
