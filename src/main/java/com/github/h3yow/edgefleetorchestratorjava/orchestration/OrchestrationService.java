package com.github.h3yow.edgefleetorchestratorjava.orchestration;

import com.github.h3yow.edgefleetorchestratorjava.DeviceNotFoundException;
import com.github.h3yow.edgefleetorchestratorjava.OwnerMismatchException;
import com.github.h3yow.edgefleetorchestratorjava.compat.CompatibilityPolicy;
import com.github.h3yow.edgefleetorchestratorjava.model.DeliveryOutcome;
import com.github.h3yow.edgefleetorchestratorjava.model.DesiredState;
import com.github.h3yow.edgefleetorchestratorjava.model.Device;
import com.github.h3yow.edgefleetorchestratorjava.model.DispatchRequest;
import com.github.h3yow.edgefleetorchestratorjava.model.DispatchResult;
import com.github.h3yow.edgefleetorchestratorjava.model.StatePhase;
import com.github.h3yow.edgefleetorchestratorjava.model.StateRequirements;
import com.github.h3yow.edgefleetorchestratorjava.registry.DeviceRegistry;
import com.github.h3yow.edgefleetorchestratorjava.session.DeviceSession;
import com.github.h3yow.edgefleetorchestratorjava.session.GatewaySimulator;
import com.github.h3yow.edgefleetorchestratorjava.session.SessionResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 期望状态编排服务。
 *
 * <p>收敛规则：
 * <ol>
 *   <li>每次下发产生单调递增的新 revision（每设备独立计数），新版本创建后旧 PENDING
 *       版本立即标记 SUPERSEDED —— 已撤销/被取代的变更不会因重试而被重新应用；</li>
 *   <li>同一设备的下发/重试/撤销在 {@link DeviceStateLedger} 的锁内串行执行，
 *       并发触发最终状态唯一确定；</li>
 *   <li>成功类结论（APPLIED / ALREADY_APPLIED）将版本置为 APPLIED，
 *       同一版本不会被反复应用；未送达/拒绝/失败保持 PENDING，仅可通过 retry 显式重试；</li>
 *   <li>兼容范围不满足 -> REJECTED_INCOMPATIBLE（消息列明原因），不静默放行；</li>
 *   <li>归属方不匹配 -> OwnerMismatchException，设备不存在 -> DeviceNotFoundException，
 *       二者与业务失败在 HTTP 层分别映射 403 / 404 / 200+结果码。</li>
 * </ol>
 */
@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);

    private final DeviceRegistry registry;
    private final CompatibilityPolicy compatibilityPolicy;
    private final GatewaySimulator gateway;
    private final Clock clock;
    private final Map<String, DeviceStateLedger> ledgers = new ConcurrentHashMap<>();

    public OrchestrationService(DeviceRegistry registry,
                                CompatibilityPolicy compatibilityPolicy,
                                GatewaySimulator gateway,
                                Clock clock) {
        this.registry = registry;
        this.compatibilityPolicy = compatibilityPolicy;
        this.gateway = gateway;
        this.clock = clock;
    }

    /** 下发新版本期望状态。 */
    public DispatchResult dispatch(DispatchRequest request) {
        Device device = resolveOwned(request.deviceId(), request.ownerId());
        DeviceStateLedger ledger = ledgerFor(request.deviceId());
        ledger.lock().lock();
        try {
            StateRequirements requirements = request.requirements() == null
                    ? StateRequirements.none()
                    : request.requirements();

            List<String> violations = compatibilityPolicy.violations(device, requirements);
            if (!violations.isEmpty()) {
                // 不兼容也登记一个版本并停留在 PENDING（带拒绝结论），保证审计可追溯，
                // 但不会送达设备；可在修复要求后下发新版本。
                long revision = ledger.nextRevision();
                DesiredState state = new DesiredState(revision, request.payload(),
                        requirements, clock.instant());
                supersedePreviousPending(ledger, revision);
                ledger.append(state);
                state.setLastOutcome(DeliveryOutcome.REJECTED_INCOMPATIBLE);
                state.setLastDetail(String.join("; ", violations));
                log.warn("dispatch REJECTED_INCOMPATIBLE: deviceId={} revision={} violations={}",
                        request.deviceId(), revision, violations);
                return new DispatchResult(DeliveryOutcome.REJECTED_INCOMPATIBLE, revision,
                        revision, state.getLastDetail());
            }

            long revision = ledger.nextRevision();
            DesiredState state = new DesiredState(revision, request.payload(),
                    requirements, clock.instant());
            supersedePreviousPending(ledger, revision);
            ledger.append(state);

            DispatchResult result = deliver(device, state);
            log.info("dispatch settled: deviceId={} revision={} outcome={}",
                    request.deviceId(), revision, result.outcome());
            return result;
        } finally {
            ledger.lock().unlock();
        }
    }

    /** 对当前最新 PENDING 版本重新发起送达；没有可重试版本时给出确定结论。 */
    public DispatchResult retry(String deviceId, String ownerId) {
        Device device = resolveOwned(deviceId, ownerId);
        DeviceStateLedger ledger = ledgerFor(deviceId);
        ledger.lock().lock();
        try {
            DesiredState pending = ledger.pending();
            if (pending == null) {
                DesiredState latest = ledger.latest();
                long current = latest == null ? 0L : latest.getRevision();
                log.info("retry ignored: deviceId={} no pending revision, currentRevision={}",
                        deviceId, current);
                return new DispatchResult(DeliveryOutcome.NOT_REVOKABLE, 0L, current,
                        "no pending desired state to retry");
            }
            DispatchResult result = deliver(device, pending);
            log.info("retry settled: deviceId={} revision={} outcome={}",
                    deviceId, pending.getRevision(), result.outcome());
            return result;
        } finally {
            ledger.lock().unlock();
        }
    }

    /**
     * 仅对指定 revision 重新送达（批量发布补投专用）。
     *
     * <p>不会产生新版本：命中 PENDING 直接重投同一 revision；
     * 已应用 -> ALREADY_APPLIED（不再触碰设备）；已撤销/被取代 -> 原样返回终态结论，
     * 保证已落定的版本不会被补投拉回生效。
     */
    public DispatchResult retryRevision(String deviceId, String ownerId, long revision) {
        Device device = resolveOwned(deviceId, ownerId);
        DeviceStateLedger ledger = ledgerFor(deviceId);
        ledger.lock().lock();
        try {
            DesiredState state = ledger.find(revision);
            if (state == null) {
                DesiredState latest = ledger.latest();
                long current = latest == null ? 0L : latest.getRevision();
                log.warn("retryRevision ignored: deviceId={} revision={} not found, currentRevision={}",
                        deviceId, revision, current);
                return new DispatchResult(DeliveryOutcome.NOT_REVOKABLE, revision, current,
                        "revision " + revision + " not found on device ledger");
            }
            switch (state.getPhase()) {
                case APPLIED -> {
                    log.info("retryRevision no-op: deviceId={} revision={} already applied",
                            deviceId, revision);
                    return new DispatchResult(DeliveryOutcome.ALREADY_APPLIED, revision,
                            revision, "revision already applied, redelivery skipped");
                }
                case SUPERSEDED -> {
                    log.info("retryRevision no-op: deviceId={} revision={} superseded",
                            deviceId, revision);
                    return new DispatchResult(DeliveryOutcome.SUPERSEDED, revision,
                            ledger.latest().getRevision(), state.getLastDetail());
                }
                case REVOKED -> {
                    log.info("retryRevision no-op: deviceId={} revision={} revoked",
                            deviceId, revision);
                    return new DispatchResult(DeliveryOutcome.REVOKED, revision,
                            ledger.latest().getRevision(), state.getLastDetail());
                }
                case PENDING -> {
                    DispatchResult result = deliver(device, state);
                    log.info("retryRevision settled: deviceId={} revision={} outcome={}",
                            deviceId, revision, result.outcome());
                    return result;
                }
                default -> throw new IllegalStateException("unknown phase " + state.getPhase());
            }
        } finally {
            ledger.lock().unlock();
        }
    }

    /** 撤销当前最新 PENDING 版本；已应用或不存在 PENDING 时返回 NOT_REVOKABLE。 */
    public DispatchResult revoke(String deviceId, String ownerId) {
        resolveOwned(deviceId, ownerId);
        DeviceStateLedger ledger = ledgerFor(deviceId);
        ledger.lock().lock();
        try {
            DesiredState pending = ledger.pending();
            if (pending == null) {
                DesiredState latest = ledger.latest();
                long current = latest == null ? 0L : latest.getRevision();
                log.info("revoke rejected: deviceId={} no pending revision, currentRevision={}",
                        deviceId, current);
                return new DispatchResult(DeliveryOutcome.NOT_REVOKABLE, 0L, current,
                        "no pending desired state to revoke");
            }
            pending.setPhase(StatePhase.REVOKED);
            pending.setLastOutcome(DeliveryOutcome.REVOKED);
            pending.setLastDetail("revoked by owner " + ownerId);
            log.info("revoke accepted: deviceId={} revision={}", deviceId, pending.getRevision());
            return new DispatchResult(DeliveryOutcome.REVOKED, pending.getRevision(),
                    pending.getRevision(), pending.getLastDetail());
        } finally {
            ledger.lock().unlock();
        }
    }

    // ---- internals ----

    private DispatchResult deliver(Device device, DesiredState state) {
        DeviceSession session = gateway.sessionFor(device.getDeviceId());
        SessionResponse response = session.apply(state);
        DeliveryOutcome outcome = response.outcome();
        state.setLastOutcome(outcome);
        state.setLastDetail(response.detail());
        switch (outcome) {
            case APPLIED, ALREADY_APPLIED -> {
                state.setPhase(StatePhase.APPLIED);
                state.setAppliedAt(clock.instant());
            }
            case NOT_DELIVERED, REJECTED_BY_DEVICE, REJECTED_INCOMPATIBLE, EXECUTION_FAILED -> {
                // 保持 PENDING，允许显式 retry；不改变最终可达状态的唯一性。
                state.setPhase(StatePhase.PENDING);
            }
            default -> {
                // SUPERSEDED / REVOKED / NOT_REVOKABLE 不应由会话返回。
                log.warn("unexpected session outcome: deviceId={} revision={} outcome={}",
                        device.getDeviceId(), state.getRevision(), outcome);
            }
        }
        return new DispatchResult(outcome, state.getRevision(), state.getRevision(),
                response.detail());
    }

    private void supersedePreviousPending(DeviceStateLedger ledger, long newRevision) {
        DesiredState previous = ledger.pending();
        if (previous != null) {
            previous.setPhase(StatePhase.SUPERSEDED);
            previous.setLastOutcome(DeliveryOutcome.SUPERSEDED);
            previous.setLastDetail("superseded by revision " + newRevision);
            log.info("previous pending superseded: oldRevision={} newRevision={}",
                    previous.getRevision(), newRevision);
        }
    }

    private DeviceStateLedger ledgerFor(String deviceId) {
        return ledgers.computeIfAbsent(deviceId, k -> new DeviceStateLedger());
    }

    /** 归属校验：不存在 -> 404；归属方不符 -> 403；顺序固定，结论可区分。 */
    final Device resolveOwned(String deviceId, String ownerId)
            throws DeviceNotFoundException, OwnerMismatchException {
        Device device = registry.find(deviceId);
        if (device == null) {
            log.warn("access rejected: deviceId={} not found (callerOwner={})", deviceId, ownerId);
            throw new DeviceNotFoundException(deviceId);
        }
        if (ownerId == null || !device.getOwnerId().equals(ownerId)) {
            log.warn("access rejected: deviceId={} ownerMismatch deviceOwner={} callerOwner={}",
                    deviceId, device.getOwnerId(), ownerId);
            throw new OwnerMismatchException(deviceId, String.valueOf(ownerId));
        }
        return device;
    }
}
