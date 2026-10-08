package com.github.h3yow.edgefleetorchestratorjava.model;

/** 批量发布中单台设备的确定结论（含越权/不存在等边界类别）。 */
public record ReleaseMemberResult(
        String deviceId,
        DeliveryOutcome outcome,
        long revision,
        String detail
) {
}
