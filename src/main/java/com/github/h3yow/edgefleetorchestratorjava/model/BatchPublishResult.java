package com.github.h3yow.edgefleetorchestratorjava.model;

import java.util.List;

/**
 * 一批发布的结果：逐台结论（顺序与冻结名单一致）+ 整批汇总。
 *
 * <p>同一 batchId 重复触发（补投/网络重发/并发重复）返回同一份确定结果，
 * 不会凭空产生新版本或重复应用。
 */
public record BatchPublishResult(
        String batchId,
        String ownerId,
        boolean resumed,
        List<BatchMemberResult> members,
        BatchSummary summary
) {
}
