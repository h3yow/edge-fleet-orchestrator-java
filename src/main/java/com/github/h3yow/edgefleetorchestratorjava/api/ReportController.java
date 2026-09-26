package com.github.h3yow.edgefleetorchestratorjava.api;

import com.github.h3yow.edgefleetorchestratorjava.model.ReportResult;
import com.github.h3yow.edgefleetorchestratorjava.report.ReportIngestService;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 设备周期上报入口。 */
@RestController
@RequestMapping("/api/devices/{deviceId}/reports")
public class ReportController {

    private final ReportIngestService reportIngest;

    public ReportController(ReportIngestService reportIngest) {
        this.reportIngest = reportIngest;
    }

    public record ReportRequest(Instant timestamp, Map<String, Object> metrics) {
    }

    @PostMapping
    public ResponseEntity<ReportResult> report(@PathVariable String deviceId,
                                               @RequestBody ReportRequest request) {
        return ResponseEntity.ok(
                reportIngest.ingest(deviceId, request.timestamp(), request.metrics()));
    }
}
