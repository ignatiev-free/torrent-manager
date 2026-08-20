package app.torrentmanager.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DownloadCompletionLogger {
    private static final Logger log = LoggerFactory.getLogger(DownloadCompletionLogger.class);

    @EventListener
    public void logCompletion(DownloadCompletedEvent event) {
        log.info("Загрузка торрента завершена: '{}' (hash={})", event.name(), event.hash());
    }
}
