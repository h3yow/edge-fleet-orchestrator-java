package com.github.h3yow.edgefleetorchestratorjava.domain;

import java.time.Instant;

/**
 * 下发收敛结论（含修订号、最终结果与判定依据）。
 */
public record DispatchResult(String deviceId, long revision, DispatchOutcome outcome,
                             RevisionState finalState, String detail, Instant decidedAt) {
}
