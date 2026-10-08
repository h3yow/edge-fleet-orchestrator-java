package com.github.h3yow.edgefleetorchestratorjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.edgefleetorchestratorjava.compat.CompatibilityPolicy;
import com.github.h3yow.edgefleetorchestratorjava.model.BatchMemberOutcome;
import com.github.h3yow.edgefleetorchestratorjava.model.BatchMemberResult;
import com.github.h3yow.edgefleetorchestratorjava.model.BatchPublishRequest;
import com.github.h3yow.edgefleetorchestratorjava.model.BatchPublishResult;
import com.github.h3yow.edgefleetorchestratorjava.model.DispatchRequest;
import com.github.h3yow.edgefleetorchestratorjava.model.StateRequirements;
import com.github.h3yow.edgefleetorchestratorjava.orchestration.BatchPublishService;
import com.github.h3yow.edgefleetorchestratorjava.orchestration.OrchestrationService;
import com.github.h3yow.edgefleetorchestratorjava.registry.DeviceRegistry;
import com.github.h3yow.edgefleetorchestratorjava.session.DeviceBehavior;
import com.github.h3yow.edgefleetorchestratorjava.session.GatewaySimulator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 批量发布：汇总、补投幂等、并发、归属与兼容边界。 */
class BatchPublishTest {

    private static final Logger log = LoggerFactory.getLogger(BatchPublishTest.class);

    private final MutableClock clock = MutableClock.startAt("2026-10-08T00:00:00Z");
    private final GatewaySimulator gateway = new GatewaySimulator();
    private final DeviceRegistry registry = new DeviceRegistry(clock, gateway);

    private BatchPublishService batchService() {
        OrchestrationService orch =
                new OrchestrationService(registry, new CompatibilityPolicy(), gateway, clock);
        return new BatchPublishService(registry, orch);
    }

    @Test
    void batchDispatchAppliesToAllMembersAndSummarizes() {
        registry.register("d1", "owner-a", "1.0.0", null);
        registry.register("d2", "owner-a", "1.0.0", null);
        BatchPublishService svc = batchService();

        BatchPublishResult result = svc.publish(new BatchPublishRequest(
                "b1", "owner-a", List.of("d1", "d2"), Map.of("mode", "eco"),
                StateRequirements.none()));
        log.info("[batch basic] batchId={} members={} summary={}",
                result.batchId(), result.members(), result.summary());

        assertEquals(2, result.summary().total());
        assertEquals(2, result.summary().applied());
        assertEquals(BatchMemberOutcome.APPLIED, result.members().get(0).outcome());
        assertEquals(1L, result.members().get(0).revision());
        assertEquals(1L, result.members().get(1).revision());
    }

    private BatchMemberResult member(BatchPublishResult r, String deviceId) {
        return r.members().stream()
                .filter(m -> m.deviceId().equals(deviceId))
                .findFirst().orElseThrow();
    }

    @Test
    void mixedOutcomesAreCountedSeparatelyAndOneFailureDoesNotFailBatch() {
        registry.register("ok", "owner-a", "1.0.0", Set.of("gps"));
        registry.register("offline", "owner-a", "1.0.0", Set.of("gps"));
        registry.register("rejecter", "owner-a", "1.0.0", Set.of("gps"));
        registry.register("failer", "owner-a", "1.0.0", Set.of("gps"));
        registry.register("old-fw", "owner-a", "1.0.0", Set.of("wifi"));
        // 显式名单里混入：别的归属方设备 + 根本不存在的设备
        registry.register("foreign", "owner-b", "1.0.0", null);
        BatchPublishService svc = batchService();
        gateway.setReachable("offline", false);
        gateway.setBehavior("rejecter", DeviceBehavior.REJECT);
        gateway.setBehavior("failer", DeviceBehavior.FAIL);
        StateRequirements reqs = new StateRequirements(Set.of("gps"), null, null);

        BatchPublishResult result = svc.publish(new BatchPublishRequest(
                "b-mix", "owner-a",
                List.of("ok", "offline", "rejecter", "failer", "old-fw", "foreign", "ghost"),
                Map.of("mode", "eco"), reqs));
        result.members().forEach(m -> log.info("[mixed] deviceId={} outcome={} rev={} blocked={} detail={}",
                m.deviceId(), m.outcome(), m.revision(), m.incompatibleBlocked(), m.detail()));
        log.info("[mixed] summary={}", result.summary());

        assertEquals(7, result.summary().total());
        assertEquals(1, result.summary().applied());
        assertEquals(1, result.summary().notDelivered());
        assertEquals(1, result.summary().rejectedByDevice());
        assertEquals(1, result.summary().executionFailed());
        assertEquals(1, result.summary().rejectedIncompatible());
        assertEquals(1, result.summary().ownerMismatch());
        assertEquals(1, result.summary().deviceNotFound());
        // 逐台结论与原因可区分
        assertEquals(BatchMemberOutcome.OWNER_MISMATCH, member(result, "foreign").outcome());
        assertTrue(member(result, "foreign").detail().contains("owner-b"));
        assertEquals(BatchMemberOutcome.DEVICE_NOT_FOUND, member(result, "ghost").outcome());
        assertEquals(BatchMemberOutcome.REJECTED_INCOMPATIBLE, member(result, "old-fw").outcome());
        assertTrue(member(result, "old-fw").incompatibleBlocked());
        assertTrue(member(result, "old-fw").detail().contains("gps"));
        // 个别设备连不上不把整批算失败：成功成员仍 APPLIED 且有自己的 revision
        assertEquals(BatchMemberOutcome.APPLIED, member(result, "ok").outcome());
        assertEquals(1L, member(result, "ok").revision());
    }

    @Test
    void resumeOnlyRetriesUnfinishedAndNeverTouchesSucceededOrCreatesNewRevision() {
        registry.register("ok", "owner-a", "1.0.0", null);
        registry.register("offline", "owner-a", "1.0.0", null);
        BatchPublishService svc = batchService();
        gateway.setReachable("offline", false);

        BatchPublishResult first = svc.publish(new BatchPublishRequest(
                "b-resume", "owner-a", List.of("ok", "offline"),
                Map.of("mode", "eco"), StateRequirements.none()));
        long okRev1 = member(first, "ok").revision();
        long offlineRev1 = member(first, "offline").revision();
        assertEquals(BatchMemberOutcome.APPLIED, member(first, "ok").outcome());
        assertEquals(BatchMemberOutcome.NOT_DELIVERED, member(first, "offline").outcome());

        // 设备恢复后“只补没做成的”
        gateway.setReachable("offline", true);
        BatchPublishResult resumed = svc.resume("b-resume", "owner-a");
        resumed.members().forEach(m -> log.info("[resume] deviceId={} outcome={} rev={}",
                m.deviceId(), m.outcome(), m.revision()));
        assertTrue(resumed.resumed());
        assertEquals(BatchMemberOutcome.APPLIED, member(resumed, "offline").outcome());
        assertEquals(offlineRev1, member(resumed, "offline").revision(), "补投复用同一版本");
        // 已成功的不再碰：结论 UP_TO_DATE（跳过），revision 不变，不产生新版本
        assertEquals(BatchMemberOutcome.UP_TO_DATE, member(resumed, "ok").outcome());
        assertEquals(okRev1, member(resumed, "ok").revision());
        assertEquals(1, resumed.summary().applied());
        assertEquals(1, resumed.summary().upToDate());
    }

    @Test
    void repeatedTriggerOfSameBatchIsIdempotentWithoutNewRevisionsOrReapply() {
        registry.register("d1", "owner-a", "1.0.0", null);
        BatchPublishService svc = batchService();

        BatchPublishRequest req = new BatchPublishRequest("b-idem", "owner-a",
                List.of("d1"), Map.of("mode", "eco"), StateRequirements.none());
        BatchPublishResult r1 = svc.publish(req);
        BatchPublishResult r2 = svc.publish(req);   // 调用方网络重发
        BatchPublishResult r3 = svc.publish(req);   // 用户手抖再点
        log.info("[idem] triggers: {} | {} | {}", r1.members(), r2.members(), r3.members());

        assertEquals(1L, member(r1, "d1").revision());
        assertEquals(1L, member(r2, "d1").revision());
        assertEquals(1L, member(r3, "d1").revision());
        assertEquals(BatchMemberOutcome.APPLIED, member(r1, "d1").outcome());
        assertEquals(BatchMemberOutcome.UP_TO_DATE, member(r2, "d1").outcome());
        assertEquals(BatchMemberOutcome.UP_TO_DATE, member(r3, "d1").outcome());
    }

    @Test
    void incompatibleMemberStaysBlockedAcrossReconnectUntilOwnerStartsNewBatch() {
        // 固件低于最低要求 + 缺能力 -> 首投不兼容并永久挡下
        registry.register("laggard", "owner-a", "1.0.0", Set.of("wifi"));
        BatchPublishService svc = batchService();
        StateRequirements reqs = new StateRequirements(Set.of("gps"), "2.0.0", null);

        BatchPublishResult first = svc.publish(new BatchPublishRequest(
                "b-block", "owner-a", List.of("laggard"), Map.of("mode", "eco"), reqs));
        BatchMemberResult m1 = member(first, "laggard");
        log.info("[blocked] first: outcome={} rev={} blocked={} detail={}",
                m1.outcome(), m1.revision(), m1.incompatibleBlocked(), m1.detail());
        assertEquals(BatchMemberOutcome.REJECTED_INCOMPATIBLE, m1.outcome());
        assertTrue(m1.incompatibleBlocked());
        long blockedRevision = m1.revision();

        // 设备经历一次掉线后重新上线（只刷新可达性/存活，不重判兼容、不放行已挡下版本）
        gateway.setReachable("laggard", false);
        gateway.setReachable("laggard", true);
        BatchPublishResult second = svc.resume("b-block", "owner-a");
        BatchMemberResult m2 = member(second, "laggard");
        log.info("[blocked] resume after reconnect: outcome={} rev={} blocked={}",
                m2.outcome(), m2.revision(), m2.incompatibleBlocked());
        assertEquals(BatchMemberOutcome.SKIPPED_BLOCKED, m2.outcome());
        assertEquals(blockedRevision, m2.revision(), "挡下成员不产生新版本");
        assertTrue(m2.incompatibleBlocked());
        assertEquals(1, second.summary().skippedBlocked());
        assertEquals(0, second.summary().rejectedIncompatible());

        // 即便设备后来固件升级/具备能力，老批次也不会自动补投生效；
        // 只能由归属方重新发起一批（新批次按当时固件/能力重新判定）。
        // 这里用“同 owner 注册一台满足要求的新设备 + 新批次”刻画重新判定路径。
        registry.register("laggard-v2", "owner-a", "2.1.0", Set.of("wifi", "gps"));
        BatchPublishResult fresh = svc.publish(new BatchPublishRequest(
                "b-fresh", "owner-a", List.of("laggard-v2"), Map.of("mode", "eco"), reqs));
        BatchMemberResult m3 = member(fresh, "laggard-v2");
        log.info("[blocked] new batch for upgraded device: outcome={} rev={}",
                m3.outcome(), m3.revision());
        assertEquals(BatchMemberOutcome.APPLIED, m3.outcome());
        assertEquals(1L, m3.revision());
    }

    @Test
    void targetByOwnerIncludesOnlyOwnedDevicesAndBatchAccessIsOwnerIsolated() {
        registry.register("a1", "owner-a", "1.0.0", null);
        registry.register("a2", "owner-a", "1.0.0", null);
        registry.register("b1", "owner-b", "1.0.0", null);
        BatchPublishService svc = batchService();

        // 不显式列名单：按 owner 圈定，只含 owner-a 的两台
        BatchPublishResult result = svc.publish(new BatchPublishRequest(
                "b-owner", "owner-a", List.of(), Map.of("mode", "eco"),
                StateRequirements.none()));
        log.info("[by-owner] members={}", result.members().stream().map(BatchMemberResult::deviceId).toList());
        assertEquals(2, result.summary().total());
        assertTrue(result.members().stream().anyMatch(m -> m.deviceId().equals("a1")));
        assertTrue(result.members().stream().anyMatch(m -> m.deviceId().equals("a2")));
        assertFalse(result.members().stream().anyMatch(m -> m.deviceId().equals("b1")));

        // 别的归属方查询/补投该批次 -> 越权
        assertThrowsOwnerMismatch(() -> svc.get("b-owner", "owner-b"));
        assertThrowsOwnerMismatch(() -> svc.resume("b-owner", "owner-b"));
        // 不存在的批次 -> 404 语义，与越权区分
        assertThrowsBatchNotFound(() -> svc.get("no-such-batch", "owner-a"));
    }

    private void assertThrowsOwnerMismatch(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected OwnerMismatchException");
        } catch (com.github.h3yow.edgefleetorchestratorjava.OwnerMismatchException expected) {
            log.info("[access] owner mismatch as expected: {}", expected.getMessage());
        }
    }

    private void assertThrowsBatchNotFound(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected BatchNotFoundException");
        } catch (com.github.h3yow.edgefleetorchestratorjava.BatchNotFoundException expected) {
            log.info("[access] batch not found as expected: {}", expected.getMessage());
        }
    }

    @Test
    void concurrentRepeatedTriggersConvergeToSingleDeterministicResult() throws Exception {
        int devices = 24;
        for (int i = 0; i < devices; i++) {
            registry.register("dev-" + i, "owner-a", "1.0.0", null);
        }
        OrchestrationService orch =
                new OrchestrationService(registry, new CompatibilityPolicy(), gateway, clock);
        BatchPublishService svc = new BatchPublishService(registry, orch);
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < devices; i++) {
            ids.add("dev-" + i);
        }
        BatchPublishRequest req = new BatchPublishRequest(
                "b-concurrent", "owner-a", ids, Map.of("mode", "eco"), StateRequirements.none());

        int threads = 12;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<BatchPublishResult>> futures =
                new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                return svc.publish(req); // 并发重复触发同一批次
            }));
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS));

        // 每个设备只能有 revision=1：没有任何线程凭空造出 revision 2，也没有重复应用
        for (var f : futures) {
            BatchPublishResult r = f.get();
            for (BatchMemberResult m : r.members()) {
                assertEquals(1L, m.revision(),
                        "device " + m.deviceId() + " got unexpected revision " + m.revision());
            }
        }
        BatchPublishResult finalView = svc.get("b-concurrent", "owner-a");
        long applied = finalView.members().stream()
                .filter(m -> m.outcome() == BatchMemberOutcome.APPLIED
                        || m.outcome() == BatchMemberOutcome.UP_TO_DATE).count();
        log.info("[concurrent] threads={} devices={} settled(up-to-date/applied)={} summary={}",
                threads, devices, applied, finalView.summary());
        assertEquals(devices, applied);
    }

    @Test
    void revokedOrSupersededMemberIsNotBroughtBackByResume() {
        registry.register("d-revoke", "owner-a", "1.0.0", null);
        registry.register("d-supersede", "owner-a", "1.0.0", null);
        OrchestrationService orch =
                new OrchestrationService(registry, new CompatibilityPolicy(), gateway, clock);
        BatchPublishService svc = new BatchPublishService(registry, orch);
        gateway.setReachable("d-revoke", false);
        gateway.setReachable("d-supersede", false);

        BatchPublishResult first = svc.publish(new BatchPublishRequest(
                "b-settled", "owner-a", List.of("d-revoke", "d-supersede"),
                Map.of("mode", "eco"), StateRequirements.none()));
        assertEquals(BatchMemberOutcome.NOT_DELIVERED, member(first, "d-revoke").outcome());
        assertEquals(BatchMemberOutcome.NOT_DELIVERED, member(first, "d-supersede").outcome());

        // 批次之外：一台被 owner 撤销，另一台被更新版本取代
        assertEquals(com.github.h3yow.edgefleetorchestratorjava.model.DeliveryOutcome.REVOKED,
                orch.revoke("d-revoke", "owner-a").outcome());
        gateway.setReachable("d-supersede", true);
        orch.dispatch(new DispatchRequest("d-supersede", "owner-a",
                Map.of("mode", "turbo"), StateRequirements.none()));

        // 设备重新上线后补投：两者都已落定，不能被拉回，且不影响另一台的成功统计
        gateway.setReachable("d-revoke", true);
        BatchPublishResult resumed = svc.resume("b-settled", "owner-a");
        resumed.members().forEach(m -> log.info("[settled] deviceId={} outcome={} rev={} detail={}",
                m.deviceId(), m.outcome(), m.revision(), m.detail()));
        assertEquals(BatchMemberOutcome.SKIPPED_SETTLED, member(resumed, "d-revoke").outcome());
        assertEquals(BatchMemberOutcome.SKIPPED_SETTLED, member(resumed, "d-supersede").outcome());
        assertEquals(2, resumed.summary().skippedSettled());
        assertEquals(0, resumed.summary().applied());
    }

    @Test
    void alreadyUpToDateDeviceIsDetectedOnFirstBatchRunAndDuplicateIdsAreDeduped() {
        registry.register("fresh", "owner-a", "1.0.0", null);
        registry.register("current", "owner-a", "1.0.0", null);
        OrchestrationService orch =
                new OrchestrationService(registry, new CompatibilityPolicy(), gateway, clock);
        BatchPublishService svc = new BatchPublishService(registry, orch);

        Map<String, Object> payload = Map.of("mode", "eco");
        // 该设备此前已通过单台下发应用了完全相同的 payload+requirements
        orch.dispatch(new DispatchRequest("current", "owner-a", payload, StateRequirements.none()));

        BatchPublishResult result = svc.publish(new BatchPublishRequest(
                "b-uptodate", "owner-a",
                List.of("fresh", "current", "current", " fresh "), // 重复 + 空白，去重保序
                payload, StateRequirements.none()));
        result.members().forEach(m -> log.info("[up-to-date] deviceId={} outcome={} rev={}",
                m.deviceId(), m.outcome(), m.revision()));

        assertEquals(2, result.summary().total(), "重复 ID 去重");
        assertEquals(BatchMemberOutcome.APPLIED, member(result, "fresh").outcome());
        assertEquals(1L, member(result, "fresh").revision());
        // 本来就是最新：不创建新版本（该设备 revision 仍是单台下发时的 1），不重发
        BatchMemberResult current = member(result, "current");
        assertEquals(BatchMemberOutcome.UP_TO_DATE, current.outcome());
        assertEquals(1L, current.revision());
        assertEquals(1, result.summary().applied());
        assertEquals(1, result.summary().upToDate());
    }

    @Test
    void emptyTargetSetIsRejectedAsBadRequest() {
        BatchPublishService svc = batchService();
        // 既没有显式名单，owner 名下也没有设备
        try {
            svc.publish(new BatchPublishRequest("b-empty", "owner-a", List.of(),
                    Map.of("mode", "eco"), StateRequirements.none()));
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            log.info("[empty] rejected as expected: {}", expected.getMessage());
        }
    }

    @Test
    void sameBatchIdWithDifferentContentIsRejectedAsConflict() {
        registry.register("c1", "owner-a", "1.0.0", null);
        BatchPublishService svc = batchService();
        svc.publish(new BatchPublishRequest("b-conflict", "owner-a", List.of("c1"),
                Map.of("mode", "eco"), StateRequirements.none()));
        try {
            // 同一 batchId 但 payload 不同：不得套用旧批次，明确冲突
            svc.publish(new BatchPublishRequest("b-conflict", "owner-a", List.of("c1"),
                    Map.of("mode", "turbo"), StateRequirements.none()));
            throw new AssertionError("expected BatchAlreadyExistsException");
        } catch (com.github.h3yow.edgefleetorchestratorjava.BatchAlreadyExistsException expected) {
            log.info("[conflict] rejected as expected: {}", expected.getMessage());
        }
    }

    @Test
    void staggeredOutOfOrderResumeEventuallySettlesOnlyUnfinishedMembers() {
        // 三台当时都不可达：首投全部 NOT_DELIVERED，各自分到 revision 1
        for (String id : List.of("s1", "s2", "s3")) {
            registry.register(id, "owner-a", "1.0.0", null);
            gateway.setReachable(id, false);
        }
        BatchPublishService svc = batchService();
        BatchPublishRequest req = new BatchPublishRequest("b-stagger", "owner-a",
                List.of("s1", "s2", "s3"), Map.of("mode", "eco"), StateRequirements.none());
        BatchPublishResult first = svc.publish(req);
        first.members().forEach(m -> assertEquals(BatchMemberOutcome.NOT_DELIVERED, m.outcome()));
        assertEquals(3, first.summary().notDelivered());

        // 乱序恢复：先 s2，再 s1（s3 始终离线）
        gateway.setReachable("s2", true);
        BatchPublishResult pass1 = svc.resume("b-stagger", "owner-a");
        log.info("[stagger] pass1 summary={}", pass1.summary());
        assertEquals(BatchMemberOutcome.APPLIED, member(pass1, "s2").outcome());
        assertEquals(BatchMemberOutcome.NOT_DELIVERED, member(pass1, "s1").outcome());
        assertEquals(BatchMemberOutcome.NOT_DELIVERED, member(pass1, "s3").outcome());

        gateway.setReachable("s1", true);
        BatchPublishResult pass2 = svc.resume("b-stagger", "owner-a");
        log.info("[stagger] pass2 summary={}", pass2.summary());
        assertEquals(BatchMemberOutcome.APPLIED, member(pass2, "s1").outcome());
        // s2 上一轮已成：本轮不再碰，呈现 upToDate，revision 不变
        assertEquals(BatchMemberOutcome.UP_TO_DATE, member(pass2, "s2").outcome());
        assertEquals(BatchMemberOutcome.NOT_DELIVERED, member(pass2, "s3").outcome());
        assertEquals(1, pass2.summary().applied());
        assertEquals(1, pass2.summary().upToDate());
        assertEquals(1, pass2.summary().notDelivered());

        // 所有设备最终 revision 仍为 1：补投从不产生新版本
        for (String id : List.of("s1", "s2", "s3")) {
            assertEquals(1L, member(pass2, id).revision());
        }
    }
}
