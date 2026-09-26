package com.github.h3yow.edgefleetorchestratorjava.sim;

import com.github.h3yow.edgefleetorchestratorjava.domain.CapabilityRange;
import com.github.h3yow.edgefleetorchestratorjava.domain.DispatchOutcome;
import com.github.h3yow.edgefleetorchestratorjava.domain.Version;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 本地模拟的“设备会话/上报通道”：不依赖任何外部资源。
 *
 * <ul>
 *   <li>{@link Fault#OFFLINE}：通道不可达，返回 NOT_DELIVERED，不产生任何应用副作用；</li>
 *   <li>{@link Fault#REJECT_CONFIG}：送达但设备拒绝配置（REJECTED_BY_DEVICE）；</li>
 *   <li>{@link Fault#EXEC_FAIL}：设备尝试执行但失败（EXECUTION_FAILED）；</li>
 *   <li>重复送达同一修订号直接返回 ALREADY_APPLIED，保证每个修订最多被应用一次。</li>
 * </ul>
 */
public final class DeviceSession {

    public enum Fault { NONE, OFFLINE, REJECT_CONFIG, EXEC_FAIL }

    private final String deviceId;
    private final Version firmware;
    private final Set<String> capabilities;
    private final Clock clock;
    private volatile Fault fault = Fault.NONE;
    private volatile long appliedRevision = -1L;
    private volatile Map<String, String> appliedConfig = Map.of();

    public DeviceSession(String deviceId, Version firmware, Set<String> capabilities, Clock clock) {
        this.deviceId = deviceId;
        this.firmware = firmware;
        this.capabilities = Set.copyOf(capabilities);
        this.clock = clock;
    }

    public String deviceId() { return deviceId; }
    public Version firmware() { return firmware; }
    public Set<String> capabilities() { return capabilities; }
    public long appliedRevision() { return appliedRevision; }
    public Map<String, String> appliedConfig() { return appliedConfig; }

    public void setFault(Fault fault) {
        this.fault = fault == null ? Fault.NONE : fault;
    }

    /**
     * 模拟一次期望状态送达与执行。
     *
     * <p>方法本身 synchronized：模拟真实设备网关“单通道串行应用”的语义，
     * 即使多个服务端线程并发送达，revision 与 config 两个字段的提交也是一个原子步骤，
     * 不会出现修订号与配置内容撕裂的最终状态。
     */
    public synchronized SessionResponse sendDesired(long revision, Map<String, String> config,
                                                     CapabilityRange range) {
        Instant at = Instant.now(clock);

        if (fault == Fault.OFFLINE) {
            return new SessionResponse(DispatchOutcome.NOT_DELIVERED,
                    "device session unreachable (simulated offline)", at);
        }

        if (revision == appliedRevision) {
            return new SessionResponse(DispatchOutcome.ALREADY_APPLIED,
                    "revision " + revision + " already applied on device", at);
        }

        if (range != null) {
            String gap = range.describeGap(firmware, capabilities);
            if (!gap.isEmpty()) {
                return new SessionResponse(DispatchOutcome.INCOMPATIBLE, "device rejects payload: " + gap, at);
            }
        }

        if (fault == Fault.REJECT_CONFIG) {
            return new SessionResponse(DispatchOutcome.REJECTED_BY_DEVICE,
                    "device rejected config (simulated policy rejection)", at);
        }

        if (fault == Fault.EXEC_FAIL) {
            return new SessionResponse(DispatchOutcome.EXECUTION_FAILED,
                    "device execution failed (simulated runtime failure)", at);
        }

        this.appliedRevision = revision;
        this.appliedConfig = Map.copyOf(config);
        return new SessionResponse(DispatchOutcome.APPLIED,
                "revision " + revision + " applied on device", at);
    }

    public record SessionResponse(DispatchOutcome outcome, String detail, Instant at) {
    }
}
