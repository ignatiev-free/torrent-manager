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
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("INTEGRATION_UNAVAILABLE"));
        assertFalse(output.getAll().contains(SECRET));
    }

    @Test
    void coversReadRoutesAndRejectsDuplicateOrStaleWrites() throws Exception {
        var qbit = mock(QBitClient.class);
        var limit = new java.util.concurrent.atomic.AtomicLong(0);
        when(qbit.getTransferInfo()).thenAnswer(call -> new TransferInfo(100, limit.get(), false));
        doAnswer(call -> { limit.set(call.getArgument(0)); return null; })
                .when(qbit).setDownloadLimit(anyLong(), anyBoolean());
        var speed = new SpeedSnapshotService(qbit, data.resolve("cursor"));
        var queries = mock(TorrentIntegrationQueries.class);
        when(queries.sendReport()).thenReturn(true);
        var cfg = new MockEnvironment()
                .withProperty("TORRENT_INTEGRATION_TOKEN", SECRET)
                .withProperty("TORRENT_INTEGRATION_SCOPES", "torrent:read")
                .withProperty("TORRENT_INTEGRATION_WRITE_TOKEN", "synthetic-write-secret");
        var mvc = MockMvcBuilders.standaloneSetup(
                new IntegrationSpeedController(speed, cfg, queries, new IntegrationOperations(data.resolve("settings")))).build();
        String read = "Bearer " + SECRET;
        String write = "Bearer synthetic-write-secret";
        for (String path : java.util.List.of("overview", "downloads", "completions", "storage", "reports/status")) {
            mvc.perform(get("/api/integration/v1/" + path)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/integration/v1/" + path).header("Authorization", write)).andExpect(status().isForbidden());
            mvc.perform(get("/api/integration/v1/" + path).header("Authorization", read)).andExpect(status().isOk());
        }
        String revision = speed.read().revision();
        var key = java.util.UUID.randomUUID();
        var put = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(PATH)
                .contentType("application/json").header("Idempotency-Key", key)
                .content("""
                         {"profile":"WORK","duration":"HOUR","expectedRevision":"%s"}
                         """.formatted(revision));
        mvc.perform(put.header("Authorization", write)).andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadLimitBps").value(8192000)).andExpect(jsonPath("$.restoreAt").exists());
        String timer = Files.readString(data.resolve("telegram-speed-timer"));
        mvc.perform(put).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPERATION_ALREADY_SEEN"));
        org.junit.jupiter.api.Assertions.assertEquals(timer, Files.readString(data.resolve("telegram-speed-timer")));
        org.junit.jupiter.api.Assertions.assertThrows(SpeedSnapshotService.StaleSpeed.class,
                () -> speed.change(new SpeedSnapshotService.Change(SpeedSnapshotService.Profile.MIN,
                        SpeedSnapshotService.Period.MANUAL, revision)));
        verify(qbit, times(1)).setDownloadLimit(8192000, false);

        var reportKey = java.util.UUID.randomUUID();
        String report = "/api/integration/v1/reports/send";
        var post = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(report);
        mvc.perform(post.header("Authorization", read)).andExpect(status().isForbidden());
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(report)
                .header("Authorization", write).header("Idempotency-Key", reportKey);
        mvc.perform(request).andExpect(status().isAccepted());
        var restarted = MockMvcBuilders.standaloneSetup(
                new IntegrationSpeedController(speed, cfg, queries, new IntegrationOperations(data.resolve("settings")))).build();
        restarted.perform(request).andExpect(status().isConflict());
        verify(queries, times(1)).sendReport();
        when(queries.sendReport()).thenThrow(new app.torrentmanager.report.DailyReportService.ReportCooldownException(
                "internal detail", java.time.Instant.now().plusSeconds(25), 25));
        restarted.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(report)
                .header("Authorization", write).header("Idempotency-Key", java.util.UUID.randomUUID()))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "25"))
                .andExpect(jsonPath("$.code").value("REPORT_COOLDOWN"));
        restarted.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(PATH)
                .header("Authorization", write).header("Idempotency-Key", java.util.UUID.randomUUID())
                .contentType("application/json").content("{malformed")).andExpect(status().isBadRequest());
        restarted.perform(get("/api/integration/v1/downloads?page=invalid").header("Authorization", read))
                .andExpect(status().isBadRequest());
    }
    private static org.springframework.test.web.servlet.MockMvc mvc(
            SpeedSnapshotService speed, MockEnvironment config) {
        return MockMvcBuilders.standaloneSetup(new IntegrationSpeedController(speed, config)).build();
    }
}
