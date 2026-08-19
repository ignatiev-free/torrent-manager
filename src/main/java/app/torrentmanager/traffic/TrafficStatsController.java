package app.torrentmanager.traffic;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/qbittorrent/traffic")
public class TrafficStatsController {
    private final TrafficStatisticsService statisticsService;

    public TrafficStatsController(TrafficStatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping
    public TrafficStatsResponse getTraffic() {
        try {
            return TrafficStatsResponse.from(statisticsService.snapshot());
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Статистика qBittorrent пока недоступна", exception);
        }
    }
}
