package com.github.h3yow.edgefleetorchestratorjava.sim;

import com.github.h3yow.edgefleetorchestratorjava.domain.Version;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地设备会话注册表：按 deviceId 维护模拟会话，并支持故障注入，
 * 供 FleetService 与测试共同使用，模拟真实边缘网关通道。
 * 重复注册同一设备将重建会话（清空已应用修订），仅用于测试初始化。
 */
public class DeviceSimulator {

    private final Clock clock;
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();

    public DeviceSimulator(Clock clock) {
        this.clock = clock;
    }

    public DeviceSession register(String deviceId, Version firmware, Set<String> capabilities) {
        DeviceSession session = new DeviceSession(deviceId, firmware,
                capabilities == null ? Set.of() : capabilities, clock);
        sessions.put(deviceId, session);
        return session;
    }

    /**
     * @return 已注册的模拟会话；不存在时返回 null（由调用方解释为未送达/未知通道）
     */
    public DeviceSession session(String deviceId) {
        return sessions.get(deviceId);
    }

    public Map<String, DeviceSession> sessions() {
        return sessions;
    }
}
