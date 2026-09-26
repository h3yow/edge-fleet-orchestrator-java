package com.github.h3yow.edgefleetorchestratorjava.service;

import com.github.h3yow.edgefleetorchestratorjava.domain.ApiException;
import com.github.h3yow.edgefleetorchestratorjava.domain.CapabilityRange;
import com.github.h3yow.edgefleetorchestratorjava.domain.CompatibilityVerdict;
import com.github.h3yow.edgefleetorchestratorjava.domain.DesiredRevision;
import com.github.h3yow.edgefleetorchestratorjava.domain.Device;
import com.github.h3yow.edgefleetorchestratorjava.domain.DispatchOutcome;
import com.github.h3yow.edgefleetorchestratorjava.domain.DispatchResult;
import com.github.h3yow.edgefleetorchestratorjava.domain.RevisionState;
import com.github.h3yow.edgefleetorchestratorjava.sim.DeviceSession;
import com.github.h3yow.edgefleetorchestratorjava.sim.DeviceSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 期望状态编排核心。
 *
 * <p>收敛规则（全部在 Device 实例锁内执行，同一设备的下发/重放/撤销/上报互斥）：
 * <ol>
 *   <li>每次下发产生单调递增的新 revision；此前所有仍 PENDING 的修订立即置 SUPERSEDED，
 *       因此同一时刻最多只有一条 PENDING 修订，最终可达状态唯一确定；</li>
 *   <li>下发前做归属方与兼容范围判定，越权 / 不存在 / 不兼容分别抛出可区分错误码；</li>
 *   <li>送达结果区分为 NOT_DELIVERED（未送达，保持 PENDING 可重试）、REJECTED_BY_DEVICE
 *       （设备拒绝，终态 REJECTED）、EXECUTION_FAILED（执行失败，终态 FAILED）、APPLIED；</li>
 *   <li>同一 revision 重复重放：设备返回 ALREADY_APPLIED，服务端终态保持 APPLIED，
 *       不会被反复应用；REVOKED / SUPERSEDED / REJECTED / FAILED 均为终态，不得重新应用；</li>
 *   <li>撤销仅对 PENDING 修订生效（终态 REVOKED），已撤销变更永远不会再被应用。</li>
 * </ol>
 */
@Service
public class FleetService {

    private static final Logger log = LoggerFactory.getLogger(FleetService.class);

    private final DeviceRegistry registry;
    private final CapabilityService capabilityService;
    private final DeviceSimulator simulator;
    private final Clock clock;

    public FleetService(DeviceRegistry registry, CapabilityService capabilityService,
                        DeviceSimulator simulator, Clock clock) {
        this.registry = registry;
        this.capabilityService = capabilityService;
        this.simulator = simulator;
        this.clock = clock;
    }

    /**
     * 创建新期望状态修订并尝试一次送达收敛。
     */
    public DispatchResult dispatch(String deviceId, String owner, Map<String, String> config,
                                   CapabilityRange acceptedRange) {
        Device device = registry.requireOwned(deviceId, owner);
        if (config == null) {
            throw new ApiException("INVALID_REQUEST", "config must not be null");
        }
        synchronized (device) {
            Instant now = Instant.now(clock);
            supersedePending(device, now, "newer revision created");

            long id = device.nextRevisionId();
            DesiredRevision revision = new DesiredRevision(id, owner, Map.copyOf(config), now);
            device.revisions().put(id, revision);
            log.info("dispatch created | deviceId={} | owner={} | revision={} | configKeys={}",
                    deviceId, owner, id, config.keySet());

            return tryDeliver(device, revision, acceptedRange);
        }
    }

    /**
     * 对既有修订重新触发送达收敛（重试 / 重复触发 / 乱序重放）。终态修订按幂等方式返回既有结论。
     */
    public DispatchResult reconcile(String deviceId, String owner, long revision,
                                    CapabilityRange acceptedRange) {
        Device device = registry.requireOwned(deviceId, owner);
        synchronized (device) {
            DesiredRevision target = device.revision(revision);
            if (target == null) {
                throw new ApiException("REVISION_NOT_FOUND",
                        "revision " + revision + " not found on device " + deviceId);
            }
            Instant now = Instant.now(clock);

            if (target.state() == RevisionState.PENDING && device.latestRevision() != target) {
                target.transition(RevisionState.SUPERSEDED, DispatchOutcome.SUPERSEDED, now,
                        "older pending revision superseded before reconcile");
            }
            return tryDeliver(device, target, acceptedRange);
        }
    }

    /**
     * 撤销一条期望状态修订；仅 PENDING 可撤销，终态修订返回其既有结论（幂等）。
     */
    public DispatchResult revoke(String deviceId, String owner, long revision) {
        Device device = registry.requireOwned(deviceId, owner);
        synchronized (device) {
            DesiredRevision target = device.revision(revision);
            if (target == null) {
                throw new ApiException("REVISION_NOT_FOUND",
                        "revision " + revision + " not found on device " + deviceId);
            }
            Instant now = Instant.now(clock);
            if (target.state() == RevisionState.PENDING) {
                target.transition(RevisionState.REVOKED, DispatchOutcome.REVOKED, now,
                        "revision revoked by owner before delivery");
                log.info("revision REVOKED | deviceId={} | revision={} | owner={}", deviceId, revision, owner);
            } else {
                log.info("revoke ignored (terminal) | deviceId={} | revision={} | state={}",
                        deviceId, revision, target.state());
            }
            return toResult(deviceId, target);
        }
    }

    public List<DesiredRevision> revisions(String deviceId, String owner) {
        Device device = registry.requireOwned(deviceId, owner);
        synchronized (device) {
            return new ArrayList<>(device.revisions().values());
        }
    }

    // ---- 锁内私有逻辑 ----------------------------------------------------

    private void supersedePending(Device device, Instant now, String why) {
        for (DesiredRevision r : device.revisions().values()) {
            if (r.state() == RevisionState.PENDING) {
                r.transition(RevisionState.SUPERSEDED, DispatchOutcome.SUPERSEDED, now, why);
                log.info("revision SUPERSEDED | deviceId={} | revision={} | basis={}", device.id(), r.revision(), why);
            }
        }
    }

    /**
     * 尝试把一条修订送达设备并推进状态。对终态修订不做任何副作用，直接返回既有结论。
     */
    private DispatchResult tryDeliver(Device device, DesiredRevision revision, CapabilityRange acceptedRange) {
        if (revision.state() != RevisionState.PENDING) {
            // 已应用的修订被再次触发：返回 ALREADY_APPLIED 表示幂等命中，状态与设备侧均无变化；
            // 其余终态（REJECTED/FAILED/SUPERSEDED/REVOKED）按其既有结论返回，不允许重新应用。
            DispatchOutcome observed = revision.state() == RevisionState.APPLIED
                    ? DispatchOutcome.ALREADY_APPLIED
                    : revision.outcome();
            log.info("reconcile idempotent | deviceId={} | revision={} | state={} | observedOutcome={}",
                    device.id(), revision.revision(), revision.state(), observed);
            return new DispatchResult(device.id(), revision.revision(), observed,
                    revision.state(), revision.detail(), revision.decidedAt());
        }

        Instant now = Instant.now(clock);

        CompatibilityVerdict verdict = capabilityService.evaluate(
                device.firmware(), device.capabilities(), acceptedRange);
        if (!verdict.compatible()) {
            revision.transition(RevisionState.REJECTED, DispatchOutcome.INCOMPATIBLE, now,
                    "rejected by compatibility gate: " + verdict.gap());
            log.warn("dispatch INCOMPATIBLE | deviceId={} | revision={} | basis={}",
                    device.id(), revision.revision(), verdict.gap());
            throw new ApiException("INCOMPATIBLE",
                    "revision " + revision.revision() + " rejected: " + verdict.gap());
        }

        DeviceSession session = simulator.session(device.id());
        if (session == null) {
            revision.transition(RevisionState.PENDING, DispatchOutcome.NOT_DELIVERED, now,
                    "no device session available");
            log.warn("dispatch NOT_DELIVERED | deviceId={} | revision={} | basis=no session",
                    device.id(), revision.revision());
            return toResult(device.id(), revision);
        }

        DeviceSession.SessionResponse response =
                session.sendDesired(revision.revision(), revision.config(), acceptedRange);
        applySessionResponse(device, revision, response, now);
        return toResult(device.id(), revision);
    }

    private void applySessionResponse(Device device, DesiredRevision revision,
                                      DeviceSession.SessionResponse response, Instant now) {
        switch (response.outcome()) {
            case APPLIED -> revision.transition(RevisionState.APPLIED, DispatchOutcome.APPLIED,
                    response.at(), response.detail());
            case ALREADY_APPLIED -> revision.transition(RevisionState.APPLIED, DispatchOutcome.ALREADY_APPLIED,
                    response.at(), response.detail());
            case REJECTED_BY_DEVICE -> revision.transition(RevisionState.REJECTED,
                    DispatchOutcome.REJECTED_BY_DEVICE, response.at(), response.detail());
            case INCOMPATIBLE -> revision.transition(RevisionState.REJECTED, DispatchOutcome.INCOMPATIBLE,
                    response.at(), response.detail());
            case EXECUTION_FAILED -> revision.transition(RevisionState.FAILED,
                    DispatchOutcome.EXECUTION_FAILED, response.at(), response.detail());
            case NOT_DELIVERED ->
                // 保持 PENDING：未送达不是终态，允许稍后 reconcile 重试
                    revision.transition(RevisionState.PENDING, DispatchOutcome.NOT_DELIVERED,
                            response.at(), response.detail());
            default -> throw new IllegalStateException("unexpected session outcome: " + response.outcome());
        }
        log.info("dispatch decided | deviceId={} | revision={} | outcome={} | state={} | basis={}",
                device.id(), revision.revision(), response.outcome(), revision.state(), response.detail());
    }

    private DispatchResult toResult(String deviceId, DesiredRevision revision) {
        return new DispatchResult(deviceId, revision.revision(), revision.outcome(),
                revision.state(), revision.detail(), revision.decidedAt());
    }
}
