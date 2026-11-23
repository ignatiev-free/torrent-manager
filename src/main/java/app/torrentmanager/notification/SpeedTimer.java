package app.torrentmanager.notification;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

record SpeedTimer(long previous, long applied, boolean alternative, Instant until) {
    static Path pathFor(Path commandState) {
        return commandState.toAbsolutePath().resolveSibling("telegram-speed-timer");
    }

    static SpeedTimer read(Path path) {
        try {
            if (!Files.exists(path)) return null;
            String[] fields = Files.readString(path).strip().split(" ");
            return new SpeedTimer(Long.parseLong(fields[0]), Long.parseLong(fields[1]),
                    Boolean.parseBoolean(fields[2]), Instant.parse(fields[3]));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
