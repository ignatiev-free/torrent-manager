package app.torrentmanager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "qbit.base-url=http://127.0.0.1:8080",
        "qbit.username=test-user",
        "qbit.password=test-password",
        "traffic-stats.initial-delay=1h",
        "traffic-stats.state-file=build/test-traffic-state.properties"
})
class TorrentManagerApplicationTests {

    @Test
    void contextLoads() {
    }

}
