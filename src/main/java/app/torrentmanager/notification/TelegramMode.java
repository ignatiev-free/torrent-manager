package app.torrentmanager.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TelegramMode {
    public enum Mode { AUTONOMOUS, INTEGRATED }
    private final Mode mode;

    public TelegramMode(@Value("${torrent-manager.telegram-mode:AUTONOMOUS}") Mode mode) {
        this.mode = mode;
    }

    public boolean integrated() { return mode == Mode.INTEGRATED; }
}
