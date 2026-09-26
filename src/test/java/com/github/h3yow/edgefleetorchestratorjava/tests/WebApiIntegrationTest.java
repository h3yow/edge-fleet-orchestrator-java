package com.github.h3yow.edgefleetorchestratorjava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 端到端入口一致性：
 * 注册 -> 上报保活 -> 下发收敛 -> 越权 403 / 不存在 404 / 不兼容结果体可区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(WebApiIntegrationTest.class);

    @Autowired
    private MockMvc mvc;

    @Test
    void fullFlowAcrossHttpEntrypoints() throws Exception {
        // 1. 注册
        mvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"edge-1","ownerId":"team-a","firmwareVersion":"1.4.0",
                                 "capabilities":["gps","ble"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"));

        // 重复注册 -> 409
        mvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"edge-1","ownerId":"team-b","firmwareVersion":"1.4.0",
                                 "capabilities":[]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DEVICE_ALREADY_REGISTERED"));

        // 2. 上报：缺失时间戳 -> 200 业务结论 REJECTED_MISSING_TIMESTAMP
        MvcResult missing = mvc.perform(post("/api/devices/edge-1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metrics\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("REJECTED_MISSING_TIMESTAMP"))
                .andReturn();
        log.info("[web] missing timestamp report => {}", missing.getResponse().getContentAsString());

        // 合法上报 -> ONLINE
        mvc.perform(post("/api/devices/edge-1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timestamp\":\"2026-09-26T00:00:00Z\",\"metrics\":{\"cpu\":3}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("ACCEPTED"))
                .andExpect(jsonPath("$.deviceStatus").value("ONLINE"));

        // 乱序上报 -> IGNORED_STALE_TIMESTAMP
        mvc.perform(post("/api/devices/edge-1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timestamp\":\"2026-09-25T00:00:00Z\",\"metrics\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("IGNORED_STALE_TIMESTAMP"));

        // 3. 下发（归属正确）-> APPLIED
        mvc.perform(post("/api/devices/edge-1/desired-state/dispatch")
                        .header("X-Owner-Id", "team-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"mode\":\"eco\"},\"requirements\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.currentRevision").value(1));

        // 4. 越权下发 -> 403 OWNER_MISMATCH
        mvc.perform(post("/api/devices/edge-1/desired-state/dispatch")
                        .header("X-Owner-Id", "team-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{},\"requirements\":{}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("OWNER_MISMATCH"));

        // 5. 不存在设备 -> 404 DEVICE_NOT_FOUND
        mvc.perform(post("/api/devices/no-such/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timestamp\":\"2026-09-26T00:00:00Z\",\"metrics\":{}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DEVICE_NOT_FOUND"));

        // 6. 不兼容下发 -> 200 REJECTED_INCOMPATIBLE（原因明确）
        mvc.perform(post("/api/devices/edge-1/desired-state/dispatch")
                        .header("X-Owner-Id", "team-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payload":{},"requirements":{
                                  "requiredCapabilities":["lora"],
                                  "minFirmware":"2.0.0","maxFirmware":"3.0.0"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED_INCOMPATIBLE"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("lora")));

        // 查询设备 -> 404 for unknown
        mvc.perform(get("/api/devices/no-such"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DEVICE_NOT_FOUND"));
    }
}
