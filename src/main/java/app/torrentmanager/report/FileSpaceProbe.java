package app.torrentmanager.report;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FileSpaceProbe {
    public Space read(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            throw new IOException("путь не существует или не является каталогом: " + path);
        }
        var store = Files.getFileStore(path);
        return new Space(store.getTotalSpace(), store.getUsableSpace());
    }

    public record Space(long totalBytes, long freeBytes) { }
}
