package com.github.h3yow.edgefleetorchestratorjava.domain;

import java.time.Instant;

/**
 * 上报处理结论（含判定依据，便于日志与接口解释）。
 */
public record ReportResult(String deviceId, long seq, Instant reportTime,
                           ReportDisposition disposition, String reason,
                           LivenessState livenessAfter) {
}
