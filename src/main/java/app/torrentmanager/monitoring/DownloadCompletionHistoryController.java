package app.torrentmanager.monitoring;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/downloads/completions")
public class DownloadCompletionHistoryController {
    private final DownloadCompletionHistoryService historyService;

    public DownloadCompletionHistoryController(DownloadCompletionHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping
    public List<DownloadCompletionRecord> recent(@RequestParam(defaultValue = "50") int limit) {
        return historyService.recent(limit);
    }
}
