package com.github.h3yow.edgefleetorchestratorjava.model;

/** 上报受理结论，不同拒绝/忽略原因可区分。 */
public enum ReportVerdict {
    ACCEPTED,
    REJECTED_MISSING_TIMESTAMP,
    IGNORED_DUPLICATE,
    IGNORED_STALE_TIMESTAMP,
    REJECTED_FUTURE_TIMESTAMP
}
