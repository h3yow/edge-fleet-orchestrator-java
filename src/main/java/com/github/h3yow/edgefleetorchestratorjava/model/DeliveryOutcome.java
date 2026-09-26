package com.github.h3yow.edgefleetorchestratorjava.model;

/**
 * 期望状态下发的收敛结论。
 * 未送达 / 被拒绝（含不兼容）/ 执行失败 三类失败明确可区分。
 */
public enum DeliveryOutcome {
    APPLIED,
    ALREADY_APPLIED,
    SUPERSEDED,
    REVOKED,
    NOT_DELIVERED,
    REJECTED_INCOMPATIBLE,
    REJECTED_BY_DEVICE,
    EXECUTION_FAILED,
    NOT_REVOKABLE
}
