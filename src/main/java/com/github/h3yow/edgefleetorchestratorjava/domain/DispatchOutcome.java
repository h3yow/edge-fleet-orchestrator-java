package com.github.h3yow.edgefleetorchestratorjava.domain;

/**
 * 一次下发动作的结果，用于区分未送达 / 被拒绝 / 执行失败 / 幂等跳过等。
 */
public enum DispatchOutcome {
    APPLIED,
    ALREADY_APPLIED,
    NOT_DELIVERED,
    REJECTED_BY_DEVICE,
    EXECUTION_FAILED,
    SUPERSEDED,
    REVOKED,
    INCOMPATIBLE
}
