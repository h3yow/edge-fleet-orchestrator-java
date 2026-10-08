package com.github.h3yow.edgefleetorchestratorjava.model;

/**
 * 批量发布中单个成员设备的确定结论。
 *
 * @param outcome            逐台结论
 * @param revision           本次发布为该设备分配/复用的版本；从未分配时为 0
 * @param detail             判定依据/原因（不兼容列明全部违规项，越权/不存在可区分说明）
 * @param incompatibleBlocked 该成员是否已被“不兼容”永久挡下；为 true 时后续补投一律跳过，
 *                            即使设备重新上线或固件/能力发生变化
 */
public record BatchMemberResult(
        String deviceId,
        BatchMemberOutcome outcome,
        long revision,
        String detail,
        boolean incompatibleBlocked
) {
}
