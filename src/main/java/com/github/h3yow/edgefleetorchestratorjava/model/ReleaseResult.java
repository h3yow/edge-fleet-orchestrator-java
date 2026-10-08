package com.github.h3yow.edgefleetorchestratorjava.model;

import java.util.List;

/** 一次批量发布（或补投）的完整结论：逐台结果 + 整批汇总。 */
public record ReleaseResult(
        String releaseId,
        String ownerId,
        List<ReleaseMemberResult> members,
        ReleaseSummary summary
) {
}
