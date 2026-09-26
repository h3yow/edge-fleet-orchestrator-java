package com.github.h3yow.edgefleetorchestratorjava.model;

import java.util.Map;

/** 下发请求：归属方 + 期望载荷 + 兼容要求。 */
public record DispatchRequest(
        String deviceId,
        String ownerId,
        Map<String, Object> payload,
        StateRequirements requirements
) {
}
