package com.github.h3yow.edgefleetorchestratorjava.domain;

/**
 * 期望状态修订（revision）的生命周期状态。
 */
public enum RevisionState {
    PENDING,
    DELIVERED,
    APPLIED,
    REJECTED,
    FAILED,
    SUPERSEDED,
    REVOKED
}
