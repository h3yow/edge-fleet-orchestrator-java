package com.github.h3yow.edgefleetorchestratorjava.api;

import com.github.h3yow.edgefleetorchestratorjava.report.LivenessService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 存活扫描运维入口（测试/演示用，本地触发一次全量 TTL 扫描）。 */
@RestController
@RequestMapping("/api/ops/liveness")
public class LivenessController {

    private final LivenessService liveness;

    public LivenessController(LivenessService liveness) {
        this.liveness = liveness;
    }

    @PostMapping("/sweep")
    public Map<String, Object> sweep() {
        int newlyExpired = liveness.sweep();
        return Map.of("newlyExpired", newlyExpired,
                "heartbeatTtlSeconds", liveness.heartbeatTtl().toSeconds(),
                "observedAt", liveness.now().toString());
    }
}
