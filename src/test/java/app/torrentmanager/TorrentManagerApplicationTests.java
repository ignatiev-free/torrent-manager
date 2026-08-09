package app.torrentmanager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "qbit.base-url=http://127.0.0.1:8080",
        "qbit.username=test-user",
        "qbit.password=test-password"
})
class TorrentManagerApplicationTests {

    @Test
    void contextLoads() {
    }

}
