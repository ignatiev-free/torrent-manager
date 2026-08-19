package app.torrentmanager.traffic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrafficStatsResponseTests {
    @Test
    void formatsBinaryTrafficUnitsForHomepage() {
        assertThat(TrafficStatsResponse.formatBytes(0)).isEqualTo("0 B");
        assertThat(TrafficStatsResponse.formatBytes(1536)).isEqualTo("1.5 KiB");
        assertThat(TrafficStatsResponse.formatBytes(12L * 1024 * 1024 * 1024 * 1024))
                .isEqualTo("12.0 TiB");
    }
}
