package com.github.h3yow.edgefleetorchestratorjava.session;

import com.github.h3yow.edgefleetorchestratorjava.model.DeliveryOutcome;
import com.github.h3yow.edgefleetorchestratorjava.model.DesiredState;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 本地设备会话与上报通道的模拟器，替代真实边缘网关链路。
 *
 * <p>为每个设备维护：
 * <ul>
 *   <li>可达性（模拟离线/断链 -> NOT_DELIVERED）；</li>
 *   <li>应答行为（接受 / 拒绝 / 执行失败）；</li>
 *   <li>已应用 revision 集合 —— 保证同一版本不会被反复应用（apply 幂等）。</li>
 * </ul>
 */
@Component
public class GatewaySimulator {

    private static final Logger log = LoggerFactory.getLogger(GatewaySimulator.class);

    private final Map<String, DeviceBehavior> behaviors = new ConcurrentHashMap<>();
    private final Map<String, Boolean> reachable = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> appliedRevisions = new ConcurrentHashMap<>();

    public void register(String deviceId) {
        behaviors.putIfAbsent(deviceId, DeviceBehavior.ACCEPT);
        reachable.putIfAbsent(deviceId, Boolean.TRUE);
        appliedRevisions.putIfAbsent(deviceId, ConcurrentHashMap.newKeySet());
    }

    public void setBehavior(String deviceId, DeviceBehavior behavior) {
        behaviors.put(deviceId, behavior);
        log.info("sim behavior updated: deviceId={} behavior={}", deviceId, behavior);
    }

    public void setReachable(String deviceId, boolean value) {
        reachable.put(deviceId, value);
        log.info("sim reachability updated: deviceId={} reachable={}", deviceId, value);
    }

    /** 测试辅助：清除设备的已应用记录。 */
    public void resetAppliedRevisions(String deviceId) {
        appliedRevisions.computeIfPresent(deviceId, (k, v) -> {
            v.clear();
            return v;
        });
    }

    /** 测试辅助：只读查看设备已应用的 revision 集合（验证"同版本只生效一次"）。 */
    public Set<Long> appliedRevisionsOf(String deviceId) {
        return Set.copyOf(appliedOf(deviceId));
    }

    public DeviceSession sessionFor(String deviceId) {
        return new SimDeviceSession(deviceId, this);
    }

    DeviceBehavior behaviorOf(String deviceId) {
        return behaviors.getOrDefault(deviceId, DeviceBehavior.ACCEPT);
    }

    boolean reachableOf(String deviceId) {
        return reachable.getOrDefault(deviceId, Boolean.TRUE);
    }

    private Set<Long> appliedOf(String deviceId) {
        return appliedRevisions.computeIfAbsent(deviceId, k -> ConcurrentHashMap.newKeySet());
    }

    /** 基于网关共享状态的会话实现；同设备多会话看到同一份已应用版本集合。 */
    static final class SimDeviceSession implements DeviceSession {

        private final String deviceId;
        private final GatewaySimulator sim;

        SimDeviceSession(String deviceId, GatewaySimulator sim) {
            this.deviceId = deviceId;
            this.sim = sim;
        }

        @Override
        public String deviceId() {
            return deviceId;
        }

        @Override
        public boolean isReachable() {
            return sim.reachableOf(deviceId);
        }

        @Override
        public SessionResponse apply(DesiredState state) {
            long revision = state.getRevision();
            // 串行化同设备的会话执行，保证并发下发时同一版本只被真正应用一次。
            synchronized (sim.appliedOf(deviceId)) {
                if (!sim.reachableOf(deviceId)) {
                    log.warn("session apply NOT_DELIVERED: deviceId={} revision={} (unreachable)",
                            deviceId, revision);
                    return new SessionResponse(DeliveryOutcome.NOT_DELIVERED,
                            "device session unreachable, state not delivered");
                }
                if (sim.appliedOf(deviceId).contains(revision)) {
                    log.info("session apply ALREADY_APPLIED: deviceId={} revision={}",
                            deviceId, revision);
                    return new SessionResponse(DeliveryOutcome.ALREADY_APPLIED,
                            "revision already applied on device, duplicate apply ignored");
                }
                DeliveryOutcome outcome = switch (sim.behaviorOf(deviceId)) {
                    case ACCEPT -> DeliveryOutcome.APPLIED;
                    case REJECT -> DeliveryOutcome.REJECTED_BY_DEVICE;
                    case FAIL -> DeliveryOutcome.EXECUTION_FAILED;
                    case UNREACHABLE -> DeliveryOutcome.NOT_DELIVERED;
                };
                if (outcome == DeliveryOutcome.APPLIED) {
                    sim.appliedOf(deviceId).add(revision);
                }
                log.info("session apply result: deviceId={} revision={} outcome={}",
                        deviceId, revision, outcome);
                return new SessionResponse(outcome, "simulated device behavior: "
                        + sim.behaviorOf(deviceId));
            }
        }
    }
}
