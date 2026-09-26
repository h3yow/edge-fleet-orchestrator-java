package com.github.h3yow.edgefleetorchestratorjava.api;

import com.github.h3yow.edgefleetorchestratorjava.model.DispatchRequest;
import com.github.h3yow.edgefleetorchestratorjava.model.DispatchResult;
import com.github.h3yow.edgefleetorchestratorjava.model.StateRequirements;
import com.github.h3yow.edgefleetorchestratorjava.orchestration.OrchestrationService;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 期望状态下发 / 重试 / 撤销入口，按 X-Owner-Id 隔离归属方。 */
@RestController
@RequestMapping("/api/devices/{deviceId}/desired-state")
public class StateController {

    private final OrchestrationService orchestration;

    public StateController(OrchestrationService orchestration) {
        this.orchestration = orchestration;
    }

    public record DispatchRequestBody(Map<String, Object> payload, StateRequirements requirements) {
    }

    @PostMapping("/dispatch")
    public DispatchResult dispatch(@PathVariable String deviceId,
                                   @RequestHeader("X-Owner-Id") String ownerId,
                                   @RequestBody DispatchRequestBody body) {
        return orchestration.dispatch(
                new DispatchRequest(deviceId, ownerId, body.payload(), body.requirements()));
    }

    @PostMapping("/retry")
    public DispatchResult retry(@PathVariable String deviceId,
                                @RequestHeader("X-Owner-Id") String ownerId) {
        return orchestration.retry(deviceId, ownerId);
    }

    @PostMapping("/revoke")
    public DispatchResult revoke(@PathVariable String deviceId,
                                 @RequestHeader("X-Owner-Id") String ownerId) {
        return orchestration.revoke(deviceId, ownerId);
    }
}
