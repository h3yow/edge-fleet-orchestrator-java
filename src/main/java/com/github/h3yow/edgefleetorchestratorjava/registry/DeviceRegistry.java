package com.github.h3yow.edgefleetorchestratorjava.registry;

import com.github.h3yow.edgefleetorchestratorjava.model.Device;
import com.github.h3yow.edgefleetorchestratorjava.session.GatewaySimulator;
import java.time.Clock;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 设备注册表（内存实现），按 deviceId 唯一。
 *
 * <p>注册成功后在 {@link GatewaySimulator} 中建立对应本地会话；
 * 重复注册同一 deviceId 会被拒绝，避免静默覆盖既有归属方/能力信息。
 */
@Component
public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    private final ConcurrentHashMap<String, Device> devices = new ConcurrentHashMap<>();
    private final Clock clock;
    private final GatewaySimulator gateway;

    public DeviceRegistry(Clock clock, GatewaySimulator gateway) {
        this.clock = clock;
        this.gateway = gateway;
    }

    public Device register(String deviceId, String ownerId, String firmwareVersion,
                           Set<String> capabilities) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
        Set<String> safeCaps = capabilities == null
                ? Set.of()
                : Collections.unmodifiableSet(Set.copyOf(capabilities));
        Device device = new Device(deviceId, ownerId, firmwareVersion, safeCaps, clock.instant());
        Device existing = devices.putIfAbsent(deviceId, device);
        if (existing != null) {
            log.warn("register rejected: deviceId={} already registered to owner={}",
                    deviceId, existing.getOwnerId());
            throw new IllegalStateException("device already registered: " + deviceId);
        }
        gateway.register(deviceId);
        log.info("register accepted: deviceId={} owner={} firmware={} capabilities={}",
                deviceId, ownerId, firmwareVersion, safeCaps);
        return device;
    }

    public Device find(String deviceId) {
        return devices.get(deviceId);
    }

    /** 返回全部已注册设备（只读视图）。 */
    public java.util.Collection<Device> all() {
        return java.util.Collections.unmodifiableCollection(devices.values());
    }

    public int size() {
        return devices.size();
    }
}
