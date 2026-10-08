package com.github.h3yow.edgefleetorchestratorjava.model;

/**
 * 一批发布的整批汇总：各类结论分别计数。
 * 成功 / 本来已是最新 / 未送达 / 不兼容被挡 / 设备拒绝 / 执行失败 / 越权 / 不存在。
 */
public record ReleaseSummary(
        int total,
        int applied,
        int alreadyApplied,
        int notDelivered,
        int rejectedIncompatible,
        int rejectedByDevice,
        int executionFailed,
        int ownerMismatch,
        int deviceNotFound,
        int superseded,
        int revoked
) {
}
