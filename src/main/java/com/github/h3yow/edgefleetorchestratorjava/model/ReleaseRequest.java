package com.github.h3yow.edgefleetorchestratorjava.model;

import java.util.List;
import java.util.Map;

/**
 * 一批设备的发布请求：同一份期望载荷 + 同一套兼容要求，面向一组目标设备。
 *
 * <p>releaseId 由调用方给定，是幂等键：同一 releaseId 的重复触发不会创建新发布，
 * 只对尚未完成的成员补投。deviceIds 为 null/空时按归属方圈定（全部属于 ownerId 的设备）。
 */
public record ReleaseRequest(
        String releaseId,
        String ownerId,
        List<String> deviceIds,
        Map<String, Object> payload,
        StateRequirements requirements
) {
}
