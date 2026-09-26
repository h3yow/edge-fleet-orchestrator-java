package com.github.h3yow.edgefleetorchestratorjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.edgefleetorchestratorjava.compat.CompatibilityPolicy;
import com.github.h3yow.edgefleetorchestratorjava.model.DeliveryOutcome;
import com.github.h3yow.edgefleetorchestratorjava.model.DispatchRequest;
import com.github.h3yow.edgefleetorchestratorjava.model.DispatchResult;
import com.github.h3yow.edgefleetorchestratorjava.model.StateRequirements;
import com.github.h3yow.edgefleetorchestratorjava.orchestration.OrchestrationService;
import com.github.h3yow.edgefleetorchestratorjava.registry.DeviceRegistry;
import com.github.h3yow.edgefleetorchestratorjava.session.DeviceBehavior;
import com.github.h3yow.edgefleetorchestratorjava.session.GatewaySimulator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 期望状态收敛：版本单调、并发唯一、失败可区分、撤销不可复活。 */
class OrchestrationTest {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationTest.class);

    private final MutableClock clock = MutableClock.startAt("2026-09-26T00:00:00Z");
    private final GatewaySimulator gateway = new GatewaySimulator();
    private final DeviceRegistry registry = new DeviceRegistry(clock, gateway);

    private OrchestrationService service() {
        return new OrchestrationService(registry, new CompatibilityPolicy(), gateway, clock);
    }

    private DispatchRequest req(String deviceId, String owner, int v) {
        return new DispatchRequest(deviceId, owner, Map.of("v", v), StateRequirements.none());
    }

    @Test
    void duplicateAndRepeatedDispatchProducesDistinctRevisionsAppliedExactlyOnce() {
        registry.register("d1", "owner-a", "1.0.0", null);
        OrchestrationService svc = service();

        DispatchResult first = svc.dispatch(req("d1", "owner-a", 1));
        DispatchResult second = svc.dispatch(req("d1", "owner-a", 1));
        DispatchResult third = svc.dispatch(req("d1", "owner-a", 1));
        log.info("[duplicate dispatch] outcomes: {} rev={} | {} rev={} | {} rev={}",
                first.outcome(), first.currentRevision(),
                second.outcome(), second.currentRevision(),
                third.outcome(), third.currentRevision());

        assertEquals(DeliveryOutcome.APPLIED, first.outcome());
        // 后两次是新版本（平台侧每次请求都创建 revision），但设备侧 revision1 已应用，
        // revision2/3 被设备应用；关键是没有任何一个 revision 被反复应用。
        assertEquals(1L, first.currentRevision());
        assertEquals(2L, second.currentRevision());
        assertEquals(3L, third.currentRevision());
        assertEquals(DeliveryOutcome.APPLIED, second.outcome());
        assertEquals(DeliveryOutcome.APPLIED, third.outcome());

        // 对同一 pending 重试在成功收敛后返回确定的"无待处理"结论
        DispatchResult retryAfter = svc.retry("d1", "owner-a");
        log.info("[retry after applied] outcome={} detail={}",
                retryAfter.outcome(), retryAfter.detail());
        assertEquals(DeliveryOutcome.NOT_REVOKABLE, retryAfter.outcome());
    }

    @Test
    void concurrentDispatchConvergesToUniqueOrderedRevisions() throws Exception {
        registry.register("d2", "owner-a", "1.0.0", null);
        OrchestrationService svc = service();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Integer> appliedRevisions = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            final int v = i;
            pool.submit(() -> {
                start.await();
                DispatchResult r = svc.dispatch(req("d2", "owner-a", v));
                if (r.outcome() == DeliveryOutcome.APPLIED) {
                    appliedRevisions.add((int) r.currentRevision());
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        log.info("[concurrent dispatch] threads={} appliedRevisions={}", threads, appliedRevisions);
        assertEquals(threads, appliedRevisions.size(), "every dispatch resolves once");
        // revision 必须恰好覆盖 1..threads，无重复无缺失 -> 唯一确定的收敛
        for (int r = 1; r <= threads; r++) {
            assertTrue(appliedRevisions.contains(r), "missing revision " + r);
        }
    }

    @Test
    void failureKindsAreDistinguishableAndRetryCanRecover() {
        registry.register("unreachable", "owner-a", "1.0.0", null);
        registry.register("rejecter", "owner-a", "1.0.0", null);
        registry.register("failer", "owner-a", "1.0.0", null);
        OrchestrationService svc = service();

        gateway.setReachable("unreachable", false);
        DispatchResult nd = svc.dispatch(req("unreachable", "owner-a", 1));
        log.info("[failure] unreachable => {} ({})", nd.outcome(), nd.detail());
        assertEquals(DeliveryOutcome.NOT_DELIVERED, nd.outcome());

        gateway.setBehavior("rejecter", DeviceBehavior.REJECT);
        DispatchResult rj = svc.dispatch(req("rejecter", "owner-a", 1));
        log.info("[failure] rejecter => {} ({})", rj.outcome(), rj.detail());
        assertEquals(DeliveryOutcome.REJECTED_BY_DEVICE, rj.outcome());

        gateway.setBehavior("failer", DeviceBehavior.FAIL);
        DispatchResult ef = svc.dispatch(req("failer", "owner-a", 1));
        log.info("[failure] failer => {} ({})", ef.outcome(), ef.detail());
        assertEquals(DeliveryOutcome.EXECUTION_FAILED, ef.outcome());

        // 恢复后仅显式 retry 才重新送达 pending 版本；同一版本再次 apply 不会被重复应用
        gateway.setReachable("unreachable", true);
        DispatchResult retry = svc.retry("unreachable", "owner-a");
        log.info("[retry recovered] unreachable => {} rev={}", retry.outcome(), retry.currentRevision());
        assertEquals(DeliveryOutcome.APPLIED, retry.outcome());
        assertEquals(1L, retry.currentRevision(), "same pending revision delivered, not a new one");

        // failer 保持失败时重试仍是 EXECUTION_FAILED
        DispatchResult retryFail = svc.retry("failer", "owner-a");
        assertEquals(DeliveryOutcome.EXECUTION_FAILED, retryFail.outcome());
    }

    @Test
    void revokedChangeIsNeverReappliedAndNewDispatchSupersedesPending() {
        registry.register("d3", "owner-a", "1.0.0", null);
        OrchestrationService svc = service();
        gateway.setReachable("d3", false);

        DispatchResult r1 = svc.dispatch(req("d3", "owner-a", 1));
        assertEquals(DeliveryOutcome.NOT_DELIVERED, r1.outcome());

        DispatchResult revoked = svc.revoke("d3", "owner-a");
        log.info("[revoke] outcome={} rev={}", revoked.outcome(), revoked.currentRevision());
        assertEquals(DeliveryOutcome.REVOKED, revoked.outcome());

        // 设备恢复后重试已撤销版本：不会重新应用
        gateway.setReachable("d3", true);
        DispatchResult retryRevoked = svc.retry("d3", "owner-a");
        log.info("[retry revoked] outcome={} detail={}", retryRevoked.outcome(), retryRevoked.detail());
        assertEquals(DeliveryOutcome.NOT_REVOKABLE, retryRevoked.outcome());

        // 新下发产生 revision2 并收敛
        DispatchResult r2 = svc.dispatch(req("d3", "owner-a", 2));
        assertEquals(DeliveryOutcome.APPLIED, r2.outcome());
        assertEquals(2L, r2.currentRevision());

        // 已应用版本不可撤销
        DispatchResult revokeApplied = svc.revoke("d3", "owner-a");
        assertEquals(DeliveryOutcome.NOT_REVOKABLE, revokeApplied.outcome());
    }

    @Test
    void incompatibleDispatchIsRejectedWithReason() {
        registry.register("d4", "owner-a", "1.0.0", Set.of("wifi"));
        OrchestrationService svc = service();
        StateRequirements strict = new StateRequirements(Set.of("gps"), "2.0.0", "3.0.0");
        DispatchResult result = svc.dispatch(
                new DispatchRequest("d4", "owner-a", Map.of("mode", "x"), strict));
        log.info("[incompatible] outcome={} detail={}", result.outcome(), result.detail());
        assertEquals(DeliveryOutcome.REJECTED_INCOMPATIBLE, result.outcome());
        assertTrue(result.detail().contains("missing required capability: gps"));
        assertTrue(result.detail().contains("below minimum"));
    }

    @Test
    void resendingSameRevisionHitsDeviceSideIdempotency() {
        registry.register("d5", "owner-a", "1.0.0", null);
        OrchestrationService svc = service();
        DispatchResult r = svc.dispatch(req("d5", "owner-a", 1));
        assertEquals(DeliveryOutcome.APPLIED, r.outcome());

        // 模拟送达响应丢失后平台用同一会话契约重投同一 revision：
        // 设备侧已应用集合命中，返回 ALREADY_APPLIED 而非再次执行。
        com.github.h3yow.edgefleetorchestratorjava.model.DesiredState state =
                new com.github.h3yow.edgefleetorchestratorjava.model.DesiredState(
                        1L, Map.of("v", 1), StateRequirements.none(), clock.instant());
        var first = gateway.sessionFor("d5").apply(state);
        var resent = gateway.sessionFor("d5").apply(state);
        log.info("[resend] same revision=1 first={} resent={}", first.outcome(), resent.outcome());
        assertEquals(DeliveryOutcome.ALREADY_APPLIED, first.outcome());
        assertEquals(DeliveryOutcome.ALREADY_APPLIED, resent.outcome());
    }
}
