package com.github.h3yow.edgefleetorchestratorjava.model;

import java.util.List;
import java.util.Map;

/**
 * 批量发布请求：同一归属方把同一份期望配置与同一套兼容要求发给一组设备。
 *
 * <p>选机方式二选一（互斥，优先显式名单）：
 * <ul>
 *   <li>{@code deviceIds}：显式列出一批设备；</li>
 *   <li>仅给 {@code ownerId}（{@code deviceIds} 为空）：圈定该归属方名下全部设备。</li>
 * </ul>
 * {@code batchId} 可由调用方提供作为幂等键；为空时服务端生成。
 */
public record BatchPublishRequest(
        String batchId,
        String ownerId,
        List<String> deviceIds,
        Map<String, Object> payload,
        StateRequirements requirements
) {
}
