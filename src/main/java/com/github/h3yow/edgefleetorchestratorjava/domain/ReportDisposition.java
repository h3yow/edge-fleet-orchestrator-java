package com.github.h3yow.edgefleetorchestratorjava.domain;

/**
 * 一次上报的处理结论。ACCEPTED 表示接受并续期；其余为可区分的拒绝 / 忽略原因，
 * 拒绝与忽略都不会刷新存活时间，也不会覆盖已有的更新状态。
 */
public enum ReportDisposition {
    ACCEPTED,
    REJECTED_UNREGISTERED,
    REJECTED_TIMESTAMP_REGRESSED,
    REJECTED_TIMESTAMP_TOO_OLD,
    REJECTED_FUTURE_SKEW,
    IGNORED_DUPLICATE
}
