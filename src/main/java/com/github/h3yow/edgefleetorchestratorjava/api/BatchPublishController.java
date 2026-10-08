package com.github.h3yow.edgefleetorchestratorjava.api;

import com.github.h3yow.edgefleetorchestratorjava.model.BatchPublishRequest;
import com.github.h3yow.edgefleetorchestratorjava.model.BatchPublishResult;
import com.github.h3yow.edgefleetorchestratorjava.orchestration.BatchPublishService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 面向一批设备的发布入口：发起/补投/查询进度。 */
@RestController
@RequestMapping("/api/batch-publishes")
public class BatchPublishController {

    private final BatchPublishService batchService;

    public BatchPublishController(BatchPublishService batchService) {
        this.batchService = batchService;
    }

    @PostMapping
    public BatchPublishResult publish(@RequestHeader("X-Owner-Id") String ownerId,
                                      @RequestBody BatchPublishRequest body) {
        return batchService.publish(new BatchPublishRequest(
                body.batchId(), ownerId, body.deviceIds(), body.payload(), body.requirements()));
    }

    @PostMapping("/{batchId}/resume")
    public BatchPublishResult resume(@PathVariable String batchId,
                                     @RequestHeader("X-Owner-Id") String ownerId) {
        return batchService.resume(batchId, ownerId);
    }

    @GetMapping("/{batchId}")
    public BatchPublishResult get(@PathVariable String batchId,
                                  @RequestHeader("X-Owner-Id") String ownerId) {
        return batchService.get(batchId, ownerId);
    }
}
