package com.github.h3yow.edgefleetorchestratorjava.model;

import java.time.Instant;

/** 一次上报的判定结果与依据。 */
public record ReportResult(
        ReportVerdict verdict,
        String reason,
        DeviceStatus deviceStatus,
        Instant observedAt
) {
}
