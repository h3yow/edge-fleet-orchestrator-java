package com.github.h3yow.edgefleetorchestratorjava.model;

/** 批量发布整批汇总：各结论各多少台，另含永久挡下与已落定跳过数。 */
public record BatchSummary(
        int total,
        int applied,
        int alreadyApplied,
        int upToDate,
        int notDelivered,
        int rejectedIncompatible,
        int rejectedByDevice,
        int executionFailed,
        int deviceNotFound,
        int ownerMismatch,
        int skippedSettled,
        int skippedBlocked
) {
}
