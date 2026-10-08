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
 * 批量发布 HTTP 入口：逐台结论 + 汇总、重复提交幂等、
 * 409 幂等键冲突 / 403 越权 / 404 不存在可区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReleaseApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ReleaseApiIntegrationTest.class);

    @Autowired
    private MockMvc mvc;

    @Test
    void releaseEndpointsAreIdempotentAndBoundariesDistinct() throws Exception {
        // 准备：两台 team-a 设备 + 一台 team-b 设备
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"rel-a1\",\"ownerId\":\"team-a\","
                                + "\"firmwareVersion\":\"1.4.0\",\"capabilities\":[\"gps\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"rel-a2\",\"ownerId\":\"team-a\","
                                + "\"firmwareVersion\":\"1.4.0\",\"capabilities\":[\"gps\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"rel-b1\",\"ownerId\":\"team-b\","
                                + "\"firmwareVersion\":\"1.4.0\",\"capabilities\":[\"gps\"]}"))
                .andExpect(status().isOk());

        // 1. 批量发布：显式列表混入他方设备与不存在的设备
        MvcResult first = mvc.perform(post("/api/releases")
                        .header("X-Owner-Id", "team-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"releaseId":"web-rel-1",
                                 "deviceIds":["rel-a1","rel-a2","rel-b1","rel-ghost"],
                                 "payload":{"mode":"eco"},
                                 "requirements":{"requiredCapabilities":["gps"]}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value("web-rel-1"))
                .andExpect(jsonPath("$.summary.total").value(4))
                .andExpect(jsonPath("$.summary.applied").value(2))
                .andExpect(jsonPath("$.summary.ownerMismatch").value(1))
                .andExpect(jsonPath("$.summary.deviceNotFound").value(1))
                .andReturn();
        log.info("[web-release] first submit => {}", first.getResponse().getContentAsString());

        // 2. 重复提交同一 releaseId（网络重发）-> 幂等，结论不变、不产生新版本
        MvcResult resent = mvc.perform(post("/api/releases")
                        .header("X-Owner-Id", "team-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"releaseId":"web-rel-1",
                                 "deviceIds":["rel-a1","rel-a2","rel-b1","rel-ghost"],
                                 "payload":{"mode":"eco"},
                                 "requirements":{"requiredCapabilities":["gps"]}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.applied").value(2))
                .andReturn();
        log.info("[web-release] idempotent resubmit => {}", resent.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(),
                resent.getResponse().getContentAsString(),
                "重复触发结果必须唯一确定");

        // 3. 显式补投入口同样幂等
        mvc.perform(post("/api/releases/web-rel-1/retry-incomplete")
                        .header("X-Owner-Id", "team-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.applied").value(2));

        // 4. 同 releaseId 不同载荷 -> 409 RELEASE_CONFLICT
        mvc.perform(post("/api/releases")
                        .header("X-Owner-Id", "team-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"releaseId":"web-rel-1","deviceIds":["rel-a1"],
                                 "payload":{"mode":"boost"},"requirements":{}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RELEASE_CONFLICT"));

        // 5. 越权补投/查询 -> 403 OWNER_MISMATCH
        mvc.perform(post("/api/releases/web-rel-1/retry-incomplete")
                        .header("X-Owner-Id", "team-b"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("OWNER_MISMATCH"));
        mvc.perform(get("/api/releases/web-rel-1").header("X-Owner-Id", "team-b"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("OWNER_MISMATCH"));

        // 6. 发布不存在 -> 404 DEVICE_NOT_FOUND
        mvc.perform(post("/api/releases/no-such-release/retry-incomplete")
                        .header("X-Owner-Id", "team-a"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DEVICE_NOT_FOUND"));

        // 7. 归属方查询 -> 200 且逐台结论可见
        mvc.perform(get("/api/releases/web-rel-1").header("X-Owner-Id", "team-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(4))
                .andExpect(jsonPath("$.summary.applied").value(2));
    }
}
