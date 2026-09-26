package com.github.h3yow.edgefleetorchestratorjava.model;

/** 一次下发/重试/撤销动作的确定结果。 */
public record DispatchResult(
        DeliveryOutcome outcome,
        long requestedRevision,
        long currentRevision,
        String detail
) {
}
