package com.github.h3yow.edgefleetorchestratorjava.model;

/**
 * 批量发布中单台成员设备的逐台结论。
 *
 * <p>除单台下发已有的送达/失败分类外，额外区分：
 * <ul>
 *   <li>{@link #UP_TO_DATE}：设备当前最新已应用状态与本次配置一致，无需重发；</li>
 *   <li>{@link #DEVICE_NOT_FOUND}：名单里的设备根本不存在（与越权严格区分）；</li>
 *   <li>{@link #OWNER_MISMATCH}：名单混入了别的归属方的设备，按越权处理；</li>
 *   <li>{@link #SKIPPED_SETTLED}：该成员对应的版本已被撤销或被新版本取代，补投不再拉回；</li>
 *   <li>{@link #SKIPPED_BLOCKED}：曾因不兼容被挡下的成员，永久跳过，只能由 owner 重新发起新发布；</li>
 *   <li>{@link #NOT_ATTEMPTED}：成员尚未参与过任何一次实际发布动作。</li>
 * </ul>
 */
public enum BatchMemberOutcome {
    APPLIED,
    ALREADY_APPLIED,
    UP_TO_DATE,
    NOT_DELIVERED,
    REJECTED_INCOMPATIBLE,
    REJECTED_BY_DEVICE,
    EXECUTION_FAILED,
    DEVICE_NOT_FOUND,
    OWNER_MISMATCH,
    SKIPPED_SETTLED,
    SKIPPED_BLOCKED,
    NOT_ATTEMPTED
}
