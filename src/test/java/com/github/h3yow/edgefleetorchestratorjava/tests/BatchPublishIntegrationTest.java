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

/** 批量发布 HTTP 入口一致性：逐台结论 + 汇总 + 补投幂等 + 403/404/400 可区分。 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchPublishIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BatchPublishIntegrationTest.class);

    @Autowired
    private MockMvc mvc;

    @Test
    void batchPublishEndToEndOverHttp() throws Exception {
        // 注册三台 owner-a + 一台 owner-b
        for (String id : new String[] {"ba-1", "ba-2", "ba-3"}) {
            mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"deviceId":"%s","ownerId":"team-a","firmwareVersion":"1.0.0",
                                     "capabilities":["gps"]}
                                    """.formatted(id)))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"ba-foreign","ownerId":"team-b","firmwareVersion":"1.0.0",
                                 "capabilities":[]}
                                """))
                .andExpect(status().isOk());

        // 发起一批：含越权设备与不存在设备
        MvcResult first = mvc.perform(post("/api/batch-publishes")
                        .header("X-Owner-Id", "team-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":"http-batch-1","payload":{"mode":"eco"},
                                 "requirements":{"requiredCapabilities":["gps"]},
                                 "deviceIds":["ba-1","ba-2","ba-3","ba-foreign","ba-ghost"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("http-batch-1"))
                .andExpect(jsonPath("$.summary.total").value(5))
                .andExpect(jsonPath("$.summary.applied").value(3))
                .andExpect(jsonPath("$.summary.ownerMismatch").value(1))
                .andExpect(jsonPath("$.summary.deviceNotFound").value(1))
                .andReturn();
        log.info("[web-batch] first => {}", first.getResponse().getContentAsString());

        // 重复触发（网络重发）：幂等，已成功的变 upToDate，无新版本
        mvc.perform(post("/api/batch-publishes")
                        .header("X-Owner-Id", "team-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":"http-batch-1","payload":{"mode":"eco"},
                                 "requirements":{"requiredCapabilities":["gps"]},
                                 "deviceIds":["ba-1","ba-2","ba-3","ba-foreign","ba-ghost"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumed").value(true))
                .andExpect(jsonPath("$.summary.upToDate").value(3))
                .andExpect(jsonPath("$.summary.applied").value(0));

        // 查询进度
        mvc.perform(get("/api/batch-publishes/http-batch-1").header("X-Owner-Id", "team-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.total").value(5));

        // 越权访问批次 -> 403
        mvc.perform(get("/api/batch-publishes/http-batch-1").header("X-Owner-Id", "team-b"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("OWNER_MISMATCH"));

        // 批次不存在 -> 404
        mvc.perform(get("/api/batch-publishes/nope").header("X-Owner-Id", "team-a"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BATCH_NOT_FOUND"));

        // 空目标集（其他 owner 名下无设备）-> 400
        mvc.perform(post("/api/batch-publishes")
                        .header("X-Owner-Id", "team-empty")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payload":{"mode":"eco"},"requirements":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}
