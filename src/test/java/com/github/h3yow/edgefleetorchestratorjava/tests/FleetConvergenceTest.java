package com.github.h3yow.edgefleetorchestratorjava.tests;

import com.github.h3yow.edgefleetorchestratorjava.domain.*;
import com.github.h3yow.edgefleetorchestratorjava.service.CapabilityService;
import com.github.h3yow.edgefleetorchestratorjava.service.DeviceRegistry;
import com.github.h3yow.edgefleetorchestratorjava.service.FleetPolicies;
import com.github.h3yow.edgefleetorchestratorjava.service.FleetService;
import com.github.h3yow.edgefleetorchestratorjava.sim.DeviceSession;
import com.github.h3yow.edgefleetorchestratorjava.sim.DeviceSimulator;
import com.github.h3yow.edgefleetorchestratorjava.testsupport.MutableClock;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 期望状态下发与收敛测试：
 * 重复/并发下发最终状态唯一、同一修订不会被反复应用、已撤销不会被重新应用，
 * 且未送达 / 被拒绝 / 执行失败三类失败可区分。
 */
class FleetConvergenceTest {

    private static final Logger log = LoggerFactory.getLogger(FleetConvergenceTest.class);
    private static final CapabilityRange RANGE = new CapabilityRange(Version.parse("2.0"),
            Version.parse("3.0"), "canbus");

    private record Harness(MutableClock clock, DeviceRegistry registry, FleetService fleet,
                           DeviceSimulator simulator) {
    }

    private Harness harness() {
        MutableClock clock = MutableClock.startAt("2026-09-26T00:00:00Z");
        FleetPolicies policies = new FleetPolicies(Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(5));
        DeviceSimulator simulator = new DeviceSimulator(clock);
        CapabilityService caps = new CapabilityService();
        DeviceRegistry registry = new DeviceRegistry(clock, policies, caps, simulator);
        FleetService fleet = new FleetService(registry, caps, simulator, clock);
        registry.register("gw-1", "acme", Version.parse("2.1.0"), Set.of("canbus"), null);
        return new Harness(clock, registry, fleet, simulator);
    }

    @Test
    void duplicateDispatchCreatesNewerRevisionAndSupersedesOlderPending() {
        Harness h = harness();
        h.simulator().session("gw-1").setFault(DeviceSession.Fault.OFFLINE);

        DispatchResult first = h.fleet().dispatch("gw-1", "acme", Map.of("v", "1"), RANGE);
        DispatchResult second = h.fleet().dispatch("gw-1", "acme", Map.of("v", "2"), RANGE);

        log.info("[case] first dispatch: revision={} outcome={} state={}",
                first.revision(), first.outcome(), first.finalState());
        log.info("[case] second dispatch: revision={} outcome={} state={}",
                second.revision(), second.outcome(), second.finalState());

        assertEquals(DispatchOutcome.NOT_DELIVERED, first.outcome());
        assertEquals(RevisionState.PENDING, first.finalState(), "not-delivered remains pending for retry");
        assertEquals(RevisionState.SUPERSEDED,
                h.registry().requireExisting("gw-1").revision(1).state(),
                "older pending revision must be superseded by the newer one");
        assertEquals(RevisionState.PENDING, second.finalState());
    }

    @Test
    void repeatedReconcileAppliesRevisionExactlyOnce() {
        Harness h = harness();
        DispatchResult dispatched = h.fleet().dispatch("gw-1", "acme", Map.of("mode", "fast"), RANGE);
        assertEquals(DispatchOutcome.APPLIED, dispatched.outcome());
        assertEquals(RevisionState.APPLIED, dispatched.finalState());

        DispatchResult replay1 = h.fleet().reconcile("gw-1", "acme", dispatched.revision(), RANGE);
        DispatchResult replay2 = h.fleet().reconcile("gw-1", "acme", dispatched.revision(), RANGE);

        log.info("[case] replay revision={} twice -> outcomes={},{} states={},{}",
                dispatched.revision(), replay1.outcome(), replay2.outcome(),
                replay1.finalState(), replay2.finalState());
        assertAll(
                () -> assertEquals(DispatchOutcome.ALREADY_APPLIED, replay1.outcome()),
                () -> assertEquals(RevisionState.APPLIED, replay1.finalState()),
                () -> assertEquals(DispatchOutcome.ALREADY_APPLIED, replay2.outcome()),
                () -> assertEquals(RevisionState.APPLIED, replay2.finalState())
        );
        // 设备侧副作用只发生一次
        assertEquals(dispatched.revision(), h.simulator().session("gw-1").appliedRevision());
        assertEquals(Map.of("mode", "fast"), h.simulator().session("gw-1").appliedConfig());
    }

    @Test
    void notDeliveredCanRecoverOnLaterReconcile() {
        Harness h = harness();
        h.simulator().session("gw-1").setFault(DeviceSession.Fault.OFFLINE);
        DispatchResult stuck = h.fleet().dispatch("gw-1", "acme", Map.of("mode", "fast"), RANGE);
        assertEquals(DispatchOutcome.NOT_DELIVERED, stuck.outcome());

        h.simulator().session("gw-1").setFault(DeviceSession.Fault.NONE);
        DispatchResult recovered = h.fleet().reconcile("gw-1", "acme", stuck.revision(), RANGE);
        log.info("[case] offline->online reconcile revision={} outcome={}",
                stuck.revision(), recovered.outcome());
        assertEquals(DispatchOutcome.APPLIED, recovered.outcome());
        assertEquals(RevisionState.APPLIED, recovered.finalState());
    }

    @Test
    void rejectedAndExecutionFailedAreDistinctTerminalStates() {
        Harness h = harness();
        h.simulator().session("gw-1").setFault(DeviceSession.Fault.REJECT_CONFIG);
        DispatchResult rejected = h.fleet().dispatch("gw-1", "acme", Map.of("k", "v"), RANGE);

        h.simulator().session("gw-1").setFault(DeviceSession.Fault.EXEC_FAIL);
        DispatchResult failed = h.fleet().dispatch("gw-1", "acme", Map.of("k", "w"), RANGE);

        log.info("[case] rejected: revision={} outcome={} state={} detail='{}'",
                rejected.revision(), rejected.outcome(), rejected.finalState(), rejected.detail());
        log.info("[case] failed:   revision={} outcome={} state={} detail='{}'",
                failed.revision(), failed.outcome(), failed.finalState(), failed.detail());
        assertAll(
                () -> assertEquals(DispatchOutcome.REJECTED_BY_DEVICE, rejected.outcome()),
                () -> assertEquals(RevisionState.REJECTED, rejected.finalState()),
                () -> assertEquals(DispatchOutcome.EXECUTION_FAILED, failed.outcome()),
                () -> assertEquals(RevisionState.FAILED, failed.finalState())
        );

        // 终态失败不得通过 reconcile 被重新应用
        DispatchResult replayRejected = h.fleet().reconcile("gw-1", "acme", rejected.revision(), RANGE);
        assertEquals(RevisionState.REJECTED, replayRejected.finalState(),
                "rejected revision must never be re-applied");
        assertEquals(-1L, h.simulator().session("gw-1").appliedRevision(),
                "rejected/failed payloads must never produce device-side application");
    }

    @Test
    void revokedPendingRevisionIsNeverApplied() {
        Harness h = harness();
        h.simulator().session("gw-1").setFault(DeviceSession.Fault.OFFLINE);
        DispatchResult pending = h.fleet().dispatch("gw-1", "acme", Map.of("k", "v"), RANGE);
        assertEquals(RevisionState.PENDING, pending.finalState());

        DispatchResult revoked = h.fleet().revoke("gw-1", "acme", pending.revision());
        h.simulator().session("gw-1").setFault(DeviceSession.Fault.NONE);
        DispatchResult replay = h.fleet().reconcile("gw-1", "acme", pending.revision(), RANGE);

        log.info("[case] revoked revision={} then reconciled -> revoke={}, replayOutcome={}, replayState={}",
                pending.revision(), revoked.outcome(), replay.outcome(), replay.finalState());
        assertAll(
                () -> assertEquals(DispatchOutcome.REVOKED, revoked.outcome()),
                () -> assertEquals(RevisionState.REVOKED, revoked.finalState()),
                () -> assertEquals(DispatchOutcome.REVOKED, replay.outcome()),
                () -> assertEquals(RevisionState.REVOKED, replay.finalState())
        );
        assertEquals(-1L, h.simulator().session("gw-1").appliedRevision(),
                "revoked change must never be applied even after the device comes back");
    }

    @Test
    void concurrentDispatchesConvergeToExactlyOneAppliedConfig() throws Exception {
        Harness h = harness();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<DispatchResult>> futures = pool.invokeAll(IntStream.range(0, threads)
                    .<Callable<DispatchResult>>mapToObj(i ->
                            () -> h.fleet().dispatch("gw-1", "acme", Map.of("n", String.valueOf(i)), RANGE))
                    .toList());
            List<DispatchResult> results = futures.stream().map(f -> {
                try {
                    return f.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).toList();

            long applied = results.stream().filter(r -> r.outcome() == DispatchOutcome.APPLIED).count();
            long pending = results.stream().filter(r -> r.finalState() == RevisionState.PENDING).count();
            log.info("[case] {} concurrent dispatches | appliedNow={} pending={}",
                    threads, applied, pending);
            log.info("[case] per-task results (revision:outcome): {}",
                    results.stream().map(r -> r.revision() + ":" + r.outcome()).toList());

            assertEquals(threads, applied, "each dispatch is a new revision and is applied once");
            assertEquals(0, pending, "nothing is left pending when the channel is healthy");

            long distinctRevisions = results.stream().map(DispatchResult::revision).distinct().count();
            assertEquals(threads, distinctRevisions,
                    "concurrent dispatches must produce distinct revision numbers");

            // 关键不变量：修订号在设备锁内按“实际取得顺序”单调分配，与任务提交顺序无关。
            // 设备上最终配置唯一确定，即“最大修订号那次下发实际携带的配置”——而不是某个提交索引。
            long lastRevision = h.registry().requireExisting("gw-1").revisionSeq();
            String winningConfig = h.registry().requireExisting("gw-1").revision(lastRevision).config().get("n");
            DeviceSession session = h.simulator().session("gw-1");
            log.info("[case] last revision={} carries config n={}; device applied revision={} config={}",
                    lastRevision, winningConfig, session.appliedRevision(), session.appliedConfig());
            assertEquals(lastRevision, session.appliedRevision());
            assertEquals(Map.of("n", winningConfig), session.appliedConfig());

            // 重复重放任何一个历史修订都不会二次应用
            for (long rev = 1; rev <= lastRevision; rev++) {
                DispatchResult replay = h.fleet().reconcile("gw-1", "acme", rev, RANGE);
                if (rev == lastRevision) {
                    assertEquals(DispatchOutcome.ALREADY_APPLIED, replay.outcome());
                }
            }
            assertEquals(lastRevision, session.appliedRevision(), "replays must not change applied revision");
            assertEquals(Map.of("n", winningConfig), session.appliedConfig(),
                    "replays must not change the converged config");
        } finally {
            pool.shutdownNow();
        }
    }
}
