package com.github.h3yow.edgefleetorchestratorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 端到端 HTTP 集成测试：本地启动，验证各入口下状态结论一致、错误码可区分。
 * 使用固定时钟（2026-09-26T00:00:00Z）使时间戳相关断言可重复，不依赖任何外部服务。
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
class FleetHttpIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(FleetHttpIntegrationTest.class);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        public Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private String registerBody(String id, String owner, String firmware, String rangeMin, String rangeMax,
                                String required) throws Exception {
        return json.writeValueAsString(Map.of(
                "deviceId", id,
                "owner", owner,
                "firmware", firmware,
                "capabilities", java.util.List.of("canbus"),
                "acceptedRange", Map.of(
                        "minFirmware", rangeMin, "maxFirmware", rangeMax,
                        "requiredCapability", required == null ? "" : required)));
    }

    @Test
    void fullLifecycleAcrossHttpEntrances() throws Exception {
        // 1) 注册
        mvc.perform(post("/api/fleet/devices").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("gw-e2e", "acme", "2.1.0", "2.0", "3.0", "canbus")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveness").value("OFFLINE"))
                .andExpect(jsonPath("$.firmware").value("2.1.0"));
        log.info("[e2e] registered gw-e2e (initially OFFLINE until first accepted report)");

        // 2) 查询不存在 → 404 NOT_FOUND；越权 → 403 FORBIDDEN_OWNER（可区分）
        mvc.perform(get("/api/fleet/devices/nope?owner=acme"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/fleet/devices/gw-e2e?owner=globex"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_OWNER"));
        log.info("[e2e] not-found vs cross-owner codes are distinct over HTTP");

        // 3) 合法上报 → ONLINE；乱序回退上报 → 拒绝但设备状态不被覆盖
        mvc.perform(post("/api/fleet/devices/gw-e2e/reports").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("seq", 1,
                                "reportTime", "2026-09-26T00:00:00Z"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("ACCEPTED"));

        mvc.perform(post("/api/fleet/devices/gw-e2e/reports").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("seq", 0,
                                "reportTime", "2026-09-25T23:59:59Z"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("REJECTED_TIMESTAMP_REGRESSED"))
                .andExpect(jsonPath("$.livenessAfter").value("ONLINE"));
        log.info("[e2e] regressed report rejected with explicit reason, liveness unchanged");

        // 未注册设备上报：得到可区分结论，而不是 500
        mvc.perform(post("/api/fleet/devices/ghost/reports").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("seq", 1,
                                "reportTime", "2026-09-26T00:00:00Z"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("REJECTED_UNREGISTERED"));

        // 4) 下发期望状态（兼容范围内）→ APPLIED；重复 reconcile → ALREADY_APPLIED
        String dispatchBody = json.writeValueAsString(Map.of(
                "owner", "acme",
                "config", Map.of("mode", "fast"),
                "acceptedRange", Map.of("minFirmware", "2.0", "maxFirmware", "3.0",
                        "requiredCapability", "canbus")));
        String dispatchResp = mvc.perform(post("/api/fleet/devices/gw-e2e/desired")
                        .contentType(MediaType.APPLICATION_JSON).content(dispatchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.revision").value(1))
                .andReturn().getResponse().getContentAsString();
        long revision = json.readTree(dispatchResp).get("revision").asLong();

        String reconcileBody = json.writeValueAsString(Map.of(
                "owner", "acme",
                "acceptedRange", Map.of("minFirmware", "2.0", "maxFirmware", "3.0",
                        "requiredCapability", "canbus")));
        mvc.perform(post("/api/fleet/devices/gw-e2e/revisions/{rev}/reconcile", revision)
                        .contentType(MediaType.APPLICATION_JSON).content(reconcileBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_APPLIED"))
                .andExpect(jsonPath("$.finalState").value("APPLIED"));
        log.info("[e2e] revision {} applied once; reconcile reports ALREADY_APPLIED", revision);

        // 5) 越权下发被拒绝，且不产生修订
        mvc.perform(post("/api/fleet/devices/gw-e2e/desired").contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody.replace("\"acme\"", "\"globex\"")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_OWNER"));

        String history = mvc.perform(get("/api/fleet/devices/gw-e2e/revisions?owner=acme"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode revisions = json.readTree(history);
        org.junit.jupiter.api.Assertions.assertEquals(1, revisions.size(),
                "forbidden dispatch must not create any revision");
        log.info("[e2e] revision history contains exactly the authorized revision: {}", history);

        // 6) 列表视图与详情视图一致
        mvc.perform(get("/api/fleet/devices?owner=acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.deviceId=='gw-e2e')].latestRevision").value(1))
                .andExpect(jsonPath("$[?(@.deviceId=='gw-e2e')].latestState").value("APPLIED"));
    }
}
