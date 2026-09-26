package com.github.h3yow.edgefleetorchestratorjava.model;

/** 期望状态版本的生命周期阶段。 */
public enum StatePhase {
    PENDING,
    APPLIED,
    SUPERSEDED,
    REVOKED
}
