package app.torrentmanager.integration;

import app.torrentmanager.notification.SpeedSnapshotService;
import app.torrentmanager.qbit.QBitClient;
import app.torrentmanager.qbit.TransferInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class IntegrationSpeedControllerTests {
    @TempDir Path data;
    private static final String PATH = "/api/integration/v1/speed";
    private static final String SECRET = "synthetic-integration-secret";

    @Test
    void enforcesScopeAndKeepsCredentialsOutOfErrors(CapturedOutput output) throws Exception {
        var qbit = mock(QBitClient.class);
        var speed = new SpeedSnapshotService(qbit, data.resolve("cursor"));
        var config = new MockEnvironment();
        mvc(speed, config).perform(get(PATH)).andExpect(status().isNotFound());
        config.setProperty("TORRENT_INTEGRATION_TOKEN", SECRET);
        var forbidden = mvc(speed, config);
        forbidden.perform(get(PATH)).andExpect(status().isUnauthorized());
        forbidden.perform(get(PATH).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        forbidden.perform(get(PATH).header("Authorization", "Bearer " + SECRET)).andExpect(status().isForbidden());
        verifyNoInteractions(qbit);

        config.setProperty("TORRENT_INTEGRATION_SCOPES", "torrent:read");
        var allowed = mvc(speed, config);
        when(qbit.getTransferInfo()).thenReturn(new TransferInfo(1024, 8192, true, 512, 0));
        Path timer = data.resolve("telegram-speed-timer");
        String timerState = "0 8192 true 2030-01-01T08:00:00Z";
        Files.writeString(timer, timerState);
        var modified = Files.getLastModifiedTime(timer);
        allowed.perform(get(PATH).header("Authorization", "Bearer " + SECRET))
                .andExpect(status().isOk()).andExpect(jsonPath("$.downloadBps").value(1024))
                .andExpect(jsonPath("$.uploadBps").value(512)).andExpect(jsonPath("$.downloadLimitBps").value(8192))
                .andExpect(jsonPath("$.alternativeMode").value(true))
                .andExpect(jsonPath("$.restoreAt").value("2030-01-01T08:00:00Z"))
                .andExpect(jsonPath("$.observedAt").exists());
        org.junit.jupiter.api.Assertions.assertEquals(timerState, Files.readString(timer));
        org.junit.jupiter.api.Assertions.assertEquals(modified, Files.getLastModifiedTime(timer));
        verify(qbit).getTransferInfo();
        verifyNoMoreInteractions(qbit);
        when(qbit.getTransferInfo()).thenThrow(new IllegalStateException(SECRET));
        allowed.perform(get(PATH).header("Authorization", "Bearer " + SECRET))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SPEED_UNAVAILABLE"));
        assertFalse(output.getAll().contains(SECRET));
    }

    private static org.springframework.test.web.servlet.MockMvc mvc(
            SpeedSnapshotService speed, MockEnvironment config) {
        return MockMvcBuilders.standaloneSetup(new IntegrationSpeedController(speed, config)).build();
    }
}
