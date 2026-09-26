package com.github.h3yow.edgefleetorchestratorjava.service;

import com.github.h3yow.edgefleetorchestratorjava.domain.ApiException;
import com.github.h3yow.edgefleetorchestratorjava.domain.CapabilityRange;
import com.github.h3yow.edgefleetorchestratorjava.domain.CompatibilityVerdict;
import com.github.h3yow.edgefleetorchestratorjava.domain.Device;
import com.github.h3yow.edgefleetorchestratorjava.domain.LivenessState;
import com.github.h3yow.edgefleetorchestratorjava.domain.Version;
import com.github.h3yow.edgefleetorchestratorjava.sim.DeviceSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 设备注册与存活状态维护：内存存储，按 deviceId 定位聚合，
 * 所有设备级写操作均在 Device 实例上 synchronized（与 FleetService / ReportService 共用同一把锁）。
 * 归属方（owner）隔离所有读写：不存在与越权返回不同错误码。
 */
@Service
public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    private final java.util.Map<String, Device> devices = new java.util.concurrent.ConcurrentHashMap<>();
    private final Clock clock;
    private final FleetPolicies policies;
    private final CapabilityService capabilityService;
    private final DeviceSimulator simulator;

    public DeviceRegistry(Clock clock, FleetPolicies policies,
                          CapabilityService capabilityService, DeviceSimulator simulator) {
        this.clock = clock;
        this.policies = policies;
        this.capabilityService = capabilityService;
        this.simulator = simulator;
    }

    /**
     * 注册设备；同一 deviceId 重复注册被拒绝。若提供 acceptedRange，
     * 注册时即做版本/能力兼容判定，不满足时以 INCOMPATIBLE 明确拒绝。
     */
    public Device register(String deviceId, String owner, Version firmware,
                           Set<String> capabilities, CapabilityRange acceptedRange) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new ApiException("INVALID_REQUEST", "deviceId must not be blank");
        }
        if (owner == null || owner.isBlank()) {
            throw new ApiException("INVALID_REQUEST", "owner must not be blank");
        }
        Set<String> caps = capabilities == null ? Set.of() : Set.copyOf(capabilities);

        CompatibilityVerdict verdict = capabilityService.evaluate(firmware, caps, acceptedRange);
        if (!verdict.compatible()) {
            throw new ApiException("INCOMPATIBLE",
                    "device " + deviceId + " registration rejected: " + verdict.gap());
        }

        Device device = new Device(deviceId, owner, firmware, caps, Instant.now(clock), policies.heartbeatTtl());
        Device existing = devices.putIfAbsent(deviceId, device);
        if (existing != null) {
            throw new ApiException("DEVICE_ALREADY_EXISTS", "device already registered: " + deviceId);
        }
        simulator.register(deviceId, firmware, caps);
        log.info("device registered | deviceId={} | owner={} | firmware={} | capabilities={} | ttl={}s",
                deviceId, owner, firmware, caps, policies.heartbeatTtl().toSeconds());
        return device;
    }

    /** 仅供设备自身上报通道使用：未注册返回 null（由上报流程解释为 REJECTED_UNREGISTERED）。 */
    public Device find(String deviceId) {
        return devices.get(deviceId);
    }

    public Device requireExisting(String deviceId) {
        Device device = devices.get(deviceId);
        if (device == null) {
            throw new ApiException("NOT_FOUND", "device not found: " + deviceId);
        }
        return device;
    }

    /**
     * 归属方校验：目标不存在 → NOT_FOUND；归属方不匹配 → FORBIDDEN_OWNER。
     * 两者错误码不同，调用方可明确区分。
     */
    public Device requireOwned(String deviceId, String owner) {
        Device device = requireExisting(deviceId);
        if (!device.owner().equals(owner)) {
            log.warn("owner check FAILED | deviceId={} | expectedOwner={} | requestOwner={}",
                    deviceId, device.owner(), owner);
            throw new ApiException("FORBIDDEN_OWNER",
                    "owner '" + owner + "' is not permitted to operate device " + deviceId);
        }
        return device;
    }

    public List<Device> listByOwner(String owner) {
        List<Device> result = new ArrayList<>();
        for (Device d : devices.values()) {
            if (d.owner().equals(owner)) {
                result.add(d);
            }
        }
        result.sort(java.util.Comparator.comparing(Device::id));
        return result;
    }

    public LivenessState livenessOf(String deviceId) {
        return requireExisting(deviceId).livenessAt(Instant.now(clock));
    }

    public Instant now() {
        return Instant.now(clock);
    }

    FleetPolicies policies() {
        return policies;
    }
}
