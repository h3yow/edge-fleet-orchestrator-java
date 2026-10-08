package com.github.h3yow.edgefleetorchestratorjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.edgefleetorchestratorjava.DeviceNotFoundException;
import com.github.h3yow.edgefleetorchestratorjava.OwnerMismatchException;
import com.github.h3yow.edgefleetorchestratorjava.compat.CompatibilityPolicy;
import com.github.h3yow.edgefleetorchestratorjava.model.DeliveryOutcome;
import com.github.h3yow.edgefleetorchestratorjava.model.DispatchRequest;
import com.github.h3yow.edgefleetorchestratorjava.model.ReleaseMemberResult;
import com.github.h3yow.edgefleetorchestratorjava.model.ReleaseRequest;
import com.github.h3yow.edgefleetorchestratorjava.model.ReleaseResult;
import com.github.h3yow.edgefleetorchestratorjava.model.StateRequirements;
import com.github.h3yow.edgefleetorchestratorjava.orchestration.OrchestrationService;
import com.github.h3yow.edgefleetorchestratorjava.registry.DeviceRegistry;
import com.github.h3yow.edgefleetorchestratorjava.release.ReleaseConflictException;
import com.github.h3yow.edgefleetorchestratorjava.release.ReleaseService;
import com.github.h3yow.edgefleetorchestratorjava.session.DeviceBehavior;
import com.github.h3yow.edgefleetorchestratorjava.session.GatewaySimulator;
import java.util.List;
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

/** 批量发布：逐台结论与汇总、只补未完成、重复触发幂等、归属/兼容边界、并发乱序。 */
class ReleaseServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ReleaseServiceTest.class);

    private final MutableClock clock = MutableClock.startAt("2026-09-26T00:00:00Z");
    private final GatewaySimulator gateway = new GatewaySimulator();
    private final DeviceRegistry registry = new DeviceRegistry(clock, gateway);
    private final OrchestrationService orchestration =
            new OrchestrationService(registry, new CompatibilityPolicy(), gateway, clock);

    private ReleaseService service() {
        return new ReleaseService(registry, orchestration, clock);
    }

    private ReleaseRequest req(String releaseId, String owner, List<String> deviceIds) {
        return new ReleaseRequest(releaseId, owner, deviceIds,
                Map.of("mode", "eco"), StateRequirements.none());
    }

    private ReleaseMemberResult member(ReleaseResult result, String deviceId) {
        return result.members().stream()
                .filter(m -> m.deviceId().equals(deviceId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no member " + deviceId));
    }

    @Test
    void batchDispatchSettlesPerDeviceWithSummaryCounts() {
        registry.register("ok-1", "owner-a", "1.4.0", Set.of("gps"));
        registry.register("ok-2", "owner-a", "1.4.0", Set.of("gps"));
        registry.register("offline", "owner-a", "1.4.0", Set.of("gps"));
        registry.register("rejecter", "owner-a", "1.4.0", Set.of("gps"));
        registry.register("failer", "owner-a", "1.4.0", Set.of("gps"));
        registry.register("legacy", "owner-a", "0.9.0", Set.of());
        registry.register("foreign", "owner-b", "1.4.0", Set.of("gps"));
        gateway.setReachable("offline", false);
        gateway.setBehavior("rejecter", DeviceBehavior.REJECT);
        gateway.setBehavior("failer", DeviceBehavior.FAIL);
        ReleaseService svc = service();

        StateRequirements requirements = new StateRequirements(Set.of("gps"), "1.0.0", "2.0.0");
        List<String> targets = List.of("ok-1", "ok-2", "offline", "rejecter",
                "failer", "legacy", "foreign", "ghost");
        log.info("[batch] input: releaseId=rel-1 owner=owner-a targets={} requirements={}",
                targets, requirements);
        ReleaseResult result = svc.submit(new ReleaseRequest("rel-1", "owner-a", targets,
                Map.of("mode", "eco"), requirements));
        result.members().forEach(m -> log.info("[batch] member: {} -> {} rev={} ({})",
                m.deviceId(), m.outcome(), m.revision(), m.detail()));
        log.info("[batch] summary: {}", result.summary());

        assertEquals(DeliveryOutcome.APPLIED, member(result, "ok-1").outcome());
        assertEquals(DeliveryOutcome.APPLIED, member(result, "ok-2").outcome());
        assertEquals(DeliveryOutcome.NOT_DELIVERED, member(result, "offline").outcome());
        assertEquals(DeliveryOutcome.REJECTED_BY_DEVICE, member(result, "rejecter").outcome());
        assertEquals(DeliveryOutcome.EXECUTION_FAILED, member(result, "failer").outcome());
        assertEquals(DeliveryOutcome.REJECTED_INCOMPATIBLE, member(result, "legacy").outcome());
        assertTrue(member(result, "legacy").detail().contains("missing required capability: gps"));
        // 越权与不存在明确区分，且都不放行
        assertEquals(DeliveryOutcome.OWNER_MISMATCH, member(result, "foreign").outcome());
        assertEquals(DeliveryOutcome.DEVICE_NOT_FOUND, member(result, "ghost").outcome());

        assertEquals(8, result.summary().total());
        assertEquals(2, result.summary().applied());
        assertEquals(1, result.summary().notDelivered());
        assertEquals(1, result.summary().rejectedByDevice());
        assertEquals(1, result.summary().executionFailed());
        assertEquals(1, result.summary().rejectedIncompatible());
        assertEquals(1, result.summary().ownerMismatch());
        assertEquals(1, result.summary().deviceNotFound());
        // 单台失败不影响其它设备：正常设备已真正生效
        assertEquals(Set.of(1L), gateway.appliedRevisionsOf("ok-1"));
        assertEquals(Set.of(1L), gateway.appliedRevisionsOf("ok-2"));
        // 越权/不存在的设备从未被触碰
        assertTrue(gateway.appliedRevisionsOf("foreign").isEmpty());
    }

    @Test
    void ownerScopedSelectionTargetsOnlyCallersDevices() {
        registry.register("a-1", "owner-a", "1.0.0", null);
        registry.register("a-2", "owner-a", "1.0.0", null);
        registry.register("b-1", "owner-b", "1.0.0", null);
        ReleaseService svc = service();

        ReleaseResult result = svc.submit(req("rel-scope", "owner-a", null));
        log.info("[owner-scope] members={} summary={}",
                result.members().stream().map(ReleaseMemberResult::deviceId).toList(),
                result.summary());

        assertEquals(2, result.summary().total());
        assertEquals(2, result.summary().applied());
        assertEquals(Set.of(1L), gateway.appliedRevisionsOf("a-1"));
        assertEquals(Set.of(1L), gateway.appliedRevisionsOf("a-2"));
        // 归属方圈定不会碰到别人的设备
        assertTrue(gateway.appliedRevisionsOf("b-1").isEmpty());
    }

    @Test
    void retryIncompleteOnlyTouchesUnfinishedMembers() {
        registry.register("done", "owner-a", "1.0.0", null);
        registry.register("offline", "owner-a", "1.0.0", null);
        gateway.setReachable("offline", false);
        ReleaseService svc = service();

        ReleaseResult first = svc.submit(req("rel-2", "owner-a", List.of("done", "offline")));
        log.info("[retry-incomplete] first: done={} offline={}",
                member(first, "done").outcome(), member(first, "offline").outcome());
        assertEquals(DeliveryOutcome.APPLIED, member(first, "done").outcome());
        assertEquals(DeliveryOutcome.NOT_DELIVERED, member(first, "offline").outcome());

        // 陷阱：若补投误碰已成功成员，行为已改为 FAIL，结论会被打翻
        gateway.setBehavior("done", DeviceBehavior.FAIL);
        gateway.setReachable("offline", true);

        ReleaseResult second = svc.submit(req("rel-2", "owner-a", List.of("done", "offline")));
        log.info("[retry-incomplete] second: done={} rev={} offline={} rev={}",
                member(second, "done").outcome(), member(second, "done").revision(),
                member(second, "offline").outcome(), member(second, "offline").revision());

        // 已成功成员不再碰：结论与版本都不变
        assertEquals(DeliveryOutcome.APPLIED, member(second, "done").outcome());
        assertEquals(1L, member(second, "done").revision());
        assertEquals(Set.of(1L), gateway.appliedRevisionsOf("done"));
        // 未完成成员补投同一 revision，不产生新版本
        assertEquals(DeliveryOutcome.APPLIED, member(second, "offline").outcome());
        assertEquals(1L, member(second, "offline").revision());
        assertEquals(Set.of(1L), gateway.appliedRevisionsOf("offline"));
        assertEquals(2, second.summary().applied());
    }

    @Test
    void duplicateTriggerIsIdempotentEvenConcurrently() throws Exception {
        for (int i = 0; i < 5; i++) {
            registry.register("dev-" + i, "owner-a", "1.0.0", null);
        }
        ReleaseService svc = service();
        List<String> targets = List.of("dev-0", "dev-1", "dev-2", "dev-3", "dev-4");

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ReleaseResult> results = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                // 模拟用户手抖/调用方网络重发：同一 releaseId 并发重复触发
                results.add(svc.submit(req("rel-dup", "owner-a", targets)));
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

        ReleaseResult any = results.iterator().next();
        log.info("[idempotent] triggers={} summary={} revisions={}",
                results.size(), any.summary(),
                any.members().stream().map(m -> m.deviceId() + "#" + m.revision()).toList());
        // 所有触发得到唯一确定的最终结果
        for (ReleaseResult r : results) {
            assertEquals(any.summary(), r.summary());
            assertEquals(any.members(), r.members());
        }
        assertEquals(5, any.summary().applied());
        // 没有凭空多出新版本、没有重复应用：每台设备只有 revision 1 且恰好生效一次
        for (int i = 0; i < 5; i++) {
            assertEquals(1L, member(any, "dev-" + i).revision());
            assertEquals(Set.of(1L), gateway.appliedRevisionsOf("dev-" + i));
        }
    }

    @Test
    void incompatibleMembersAreNeverAutoRetriedEvenAfterReconnect() {
        registry.register("legacy", "owner-a", "0.9.0", Set.of());
        registry.register("modern", "owner-a", "1.5.0", Set.of("gps"));
        ReleaseService svc = service();
        StateRequirements strict = new StateRequirements(Set.of("gps"), "1.0.0", "2.0.0");
        List<String> targets = List.of("legacy", "modern");

        ReleaseResult first = svc.submit(new ReleaseRequest("rel-3", "owner-a", targets,
                Map.of("mode", "eco"), strict));
        log.info("[incompatible] first: legacy={} ({}) modern={}",
                member(first, "legacy").outcome(), member(first, "legacy").detail(),
                member(first, "modern").outcome());
        assertEquals(DeliveryOutcome.REJECTED_INCOMPATIBLE, member(first, "legacy").outcome());

        // 设备掉线又上线：只刷新存活，不改变发布结论，也不补投被挡成员
        gateway.setReachable("legacy", false);
        gateway.setReachable("legacy", true);
        ReleaseResult second = svc.submit(new ReleaseRequest("rel-3", "owner-a", targets,
                Map.of("mode", "eco"), strict));
        svc.retryIncomplete("rel-3", "owner-a");
        log.info("[incompatible] after reconnect+retrigger: legacy={} applied={}",
                member(second, "legacy").outcome(), gateway.appliedRevisionsOf("legacy"));
        assertEquals(DeliveryOutcome.REJECTED_INCOMPATIBLE, member(second, "legacy").outcome());
        assertTrue(gateway.appliedRevisionsOf("legacy").isEmpty(), "被挡成员永不自动补投生效");

        // 要改变其期望状态，只能由归属方发起新一批发布（新 releaseId）
        ReleaseResult newRelease = svc.submit(new ReleaseRequest("rel-3-fix", "owner-a",
                List.of("legacy"), Map.of("mode", "eco"), StateRequirements.none()));
        log.info("[incompatible] new release: legacy={} rev={}",
                member(newRelease, "legacy").outcome(), member(newRelease, "legacy").revision());
        assertEquals(DeliveryOutcome.APPLIED, member(newRelease, "legacy").outcome());
        assertEquals(2L, member(newRelease, "legacy").revision(), "新发布产生新的单调版本");
        assertEquals(Set.of(2L), gateway.appliedRevisionsOf("legacy"));
    }

    @Test
    void revokedOrSupersededMembersAreNotRevivedByRetry() {
        registry.register("revoked-dev", "owner-a", "1.0.0", null);
        registry.register("superseded-dev", "owner-a", "1.0.0", null);
        gateway.setReachable("revoked-dev", false);
        gateway.setReachable("superseded-dev", false);
        ReleaseService svc = service();
        List<String> targets = List.of("revoked-dev", "superseded-dev");

        ReleaseResult first = svc.submit(req("rel-4", "owner-a", targets));
        assertEquals(DeliveryOutcome.NOT_DELIVERED, member(first, "revoked-dev").outcome());
        assertEquals(DeliveryOutcome.NOT_DELIVERED, member(first, "superseded-dev").outcome());

        // 发布之外：一台被归属方撤销，一台被更新版本取代并生效
        orchestration.revoke("revoked-dev", "owner-a");
        gateway.setReachable("superseded-dev", true);
        orchestration.dispatch(new DispatchRequest("superseded-dev", "owner-a",
                Map.of("mode", "boost"), StateRequirements.none()));
        gateway.setReachable("revoked-dev", true);

        ReleaseResult retried = svc.retryIncomplete("rel-4", "owner-a");
        log.info("[no-revive] revoked-dev={} superseded-dev={} appliedRevoked={} appliedSuperseded={}",
                member(retried, "revoked-dev").outcome(), member(retried, "superseded-dev").outcome(),
                gateway.appliedRevisionsOf("revoked-dev"), gateway.appliedRevisionsOf("superseded-dev"));

        assertEquals(DeliveryOutcome.REVOKED, member(retried, "revoked-dev").outcome());
        assertEquals(DeliveryOutcome.SUPERSEDED, member(retried, "superseded-dev").outcome());
        // 已落定的版本不会被补投拉回生效
        assertTrue(gateway.appliedRevisionsOf("revoked-dev").isEmpty());
        assertEquals(Set.of(2L), gateway.appliedRevisionsOf("superseded-dev"));
        assertEquals(1, retried.summary().revoked());
        assertEquals(1, retried.summary().superseded());
    }

    @Test
    void ownershipAndExistenceBoundariesOnReleaseLevel() {
        registry.register("mine", "owner-a", "1.0.0", null);
        ReleaseService svc = service();
        svc.submit(req("rel-5", "owner-a", List.of("mine")));

        // 别的归属方重复触发同一批 -> 越权（403 语义），与"不存在"区分
        OwnerMismatchException omm = assertThrows(OwnerMismatchException.class,
                () -> svc.submit(req("rel-5", "owner-b", List.of("mine"))));
        log.info("[boundary] cross-owner retrigger rejected: {}", omm.getMessage());
        assertThrows(OwnerMismatchException.class,
                () -> svc.retryIncomplete("rel-5", "owner-b"));
        assertThrows(OwnerMismatchException.class, () -> svc.get("rel-5", "owner-b"));

        // 同 releaseId 不同载荷 -> 幂等键冲突（409 语义）
        ReleaseConflictException conflict = assertThrows(ReleaseConflictException.class,
                () -> svc.submit(new ReleaseRequest("rel-5", "owner-a", List.of("mine"),
                        Map.of("mode", "boost"), StateRequirements.none())));
        log.info("[boundary] conflicting spec rejected: {}", conflict.getMessage());

        // 发布不存在 -> 404 语义
        assertThrows(DeviceNotFoundException.class,
                () -> svc.retryIncomplete("no-such-release", "owner-a"));

        // 冲突与越权尝试都没有影响原批结论
        ReleaseResult current = svc.get("rel-5", "owner-a");
        assertEquals(1, current.summary().applied());
        assertEquals(Set.of(1L), gateway.appliedRevisionsOf("mine"));
    }

    @Test
    void deviceReonlineDoesNotReplaySettledVersions() {
        registry.register("flaky", "owner-a", "1.0.0", null);
        registry.register("steady", "owner-a", "1.0.0", null);
        gateway.setReachable("flaky", false);
        ReleaseService svc = service();

        ReleaseResult first = svc.submit(req("rel-6", "owner-a", List.of("flaky", "steady")));
        assertEquals(DeliveryOutcome.NOT_DELIVERED, member(first, "flaky").outcome());
        assertEquals(DeliveryOutcome.APPLIED, member(first, "steady").outcome());

        // 设备重新上线只刷新存活判断：不触发任何重放
        gateway.setReachable("flaky", true);
        gateway.setReachable("steady", false);
        gateway.setReachable("steady", true);
        log.info("[reonline] flaky applied={} steady applied={} (无任何重触发)",
                gateway.appliedRevisionsOf("flaky"), gateway.appliedRevisionsOf("steady"));
        assertTrue(gateway.appliedRevisionsOf("flaky").isEmpty(), "上线不自动补投");
        assertEquals(Set.of(1L), gateway.appliedRevisionsOf("steady"), "已生效版本不被重放");

        // 只有显式补投才让 flaky 收敛
        ReleaseResult retried = svc.retryIncomplete("rel-6", "owner-a");
        assertEquals(DeliveryOutcome.APPLIED, member(retried, "flaky").outcome());
        assertEquals(Set.of(1L), gateway.appliedRevisionsOf("flaky"));
    }

    @Test
    void concurrentRetryAfterReconnectAppliesExactlyOnce() throws Exception {
        registry.register("flaky", "owner-a", "1.0.0", null);
        gateway.setReachable("flaky", false);
        ReleaseService svc = service();
        ReleaseResult first = svc.submit(req("rel-7", "owner-a", List.of("flaky")));
        assertEquals(DeliveryOutcome.NOT_DELIVERED, member(first, "flaky").outcome());

        gateway.setReachable("flaky", true);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ReleaseResult> results = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            final boolean viaSubmit = i % 2 == 0;
            pool.submit(() -> {
                start.await();
                // 乱序并发：一半走重复 submit，一半走显式 retry-incomplete
                if (viaSubmit) {
                    results.add(svc.submit(req("rel-7", "owner-a", List.of("flaky"))));
                } else {
                    results.add(svc.retryIncomplete("rel-7", "owner-a"));
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

        ReleaseResult any = results.iterator().next();
        log.info("[concurrent-retry] triggers={} final={} rev={} applied={}",
                results.size(), member(any, "flaky").outcome(),
                member(any, "flaky").revision(), gateway.appliedRevisionsOf("flaky"));
        for (ReleaseResult r : results) {
            assertEquals(any.members(), r.members(), "并发补投最终结果唯一确定");
        }
        assertEquals(DeliveryOutcome.APPLIED, member(any, "flaky").outcome());
        assertEquals(1L, member(any, "flaky").revision(), "补投不产生新版本");
        assertEquals(Set.of(1L), gateway.appliedRevisionsOf("flaky"), "同一版本只真正生效一次");
    }
}
