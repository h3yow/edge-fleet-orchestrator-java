package com.github.h3yow.edgefleetorchestratorjava.api;

import com.github.h3yow.edgefleetorchestratorjava.model.ReleaseRequest;
import com.github.h3yow.edgefleetorchestratorjava.model.ReleaseResult;
import com.github.h3yow.edgefleetorchestratorjava.model.StateRequirements;
import com.github.h3yow.edgefleetorchestratorjava.release.ReleaseService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批量发布入口。
 *
 * <p>POST /api/releases 幂等：releaseId 是幂等键，重复提交只补投未完成成员；
 * 也可用 POST /api/releases/{releaseId}/retry-incomplete 显式补投。
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releaseService;

    public ReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    public record ReleaseRequestBody(
            String releaseId,
            List<String> deviceIds,
            Map<String, Object> payload,
            StateRequirements requirements) {
    }

    @PostMapping
    public ReleaseResult submit(@RequestHeader("X-Owner-Id") String ownerId,
                                @RequestBody ReleaseRequestBody body) {
        return releaseService.submit(new ReleaseRequest(
                body.releaseId(), ownerId, body.deviceIds(), body.payload(), body.requirements()));
    }

    @PostMapping("/{releaseId}/retry-incomplete")
    public ReleaseResult retryIncomplete(@PathVariable String releaseId,
                                         @RequestHeader("X-Owner-Id") String ownerId) {
        return releaseService.retryIncomplete(releaseId, ownerId);
    }

    @GetMapping("/{releaseId}")
    public ReleaseResult get(@PathVariable String releaseId,
                             @RequestHeader("X-Owner-Id") String ownerId) {
        return releaseService.get(releaseId, ownerId);
    }
}
