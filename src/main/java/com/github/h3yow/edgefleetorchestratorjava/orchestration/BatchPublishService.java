package com.github.h3yow.edgefleetorchestratorjava.orchestration;

import com.github.h3yow.edgefleetorchestratorjava.BatchAlreadyExistsException;
import com.github.h3yow.edgefleetorchestratorjava.BatchNotFoundException;
import com.github.h3yow.edgefleetorchestratorjava.OwnerMismatchException;
import com.github.h3yow.edgefleetorchestratorjava.model.BatchMemberOutcome;
import com.github.h3yow.edgefleetorchestratorjava.model.BatchMemberResult;
import com.github.h3yow.edgefleetorchestratorjava.model.BatchPublishRequest;
import com.github.h3yow.edgefleetorchestratorjava.model.BatchPublishResult;
import com.github.h3yow.edgefleetorchestratorjava.model.BatchSummary;
import com.github.h3yow.edgefleetorchestratorjava.model.DeliveryOutcome;
import com.github.h3yow.edgefleetorchestratorjava.model.Device;
import com.github.h3yow.edgefleetorchestratorjava.model.StateRequirements;
import com.github.h3yow.edgefleetorchestratorjava.registry.DeviceRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 面向一批设备的发布服务。
 *
 * <p>核心模型与保证：
 * <ul>
 *   <li><b>一批发布 ↔ 每设备一个版本</b>：创建批次时冻结成员名单（显式列表或按 owner 圈定，
 *       去重保序）；每台设备首次实际处理时在其设备账本锁内分配一个单调 revision，
 *       不同设备之间并行、互不阻塞。</li>
 *   <li><b>只补未完成</b>：对同一 batchId 的再次触发（重复 publish / resume / 网络重发 /
 *       并发重复）只处理尚未做成的成员；已成功的不碰、更不重发；已撤销或被更新版本取代的
 *       成员标记 SKIPPED_SETTLED 不被拉回。</li>
 *   <li><b>补投幂等</b>：每批次一把锁，触发串行化；成员结论被记住，重复触发最终结果唯一确定，
 *       不会凭空产生新版本或重复应用。</li>
 *   <li><b>不兼容永久挡下</b>：成员被标 {@code incompatibleBlocked} 后，后续补投一律
 *       SKIPPED_BLOCKED，即使设备重新上线或固件/能力发生变化；要改变只能由 owner 发起新批次。</li>
 *   <li><b>归属与存在性逐台区分</b>：设备不存在 -> DEVICE_NOT_FOUND；属于别的 owner ->
 *       OWNER_MISMATCH（越权），二者逐台记录且可区分；个别设备失败不影响整批其它成员。</li>
 * </ul>
 */
@Service
public class BatchPublishService {

    private static final Logger log = LoggerFactory.getLogger(BatchPublishService.class);

    private final DeviceRegistry registry;
    private final OrchestrationService orchestration;
    private final Map<String, Batch> batches = new ConcurrentHashMap<>();

    public BatchPublishService(DeviceRegistry registry,
                               OrchestrationService orchestration) {
        this.registry = registry;
        this.orchestration = orchestration;
    }

    /** 发起一批发布（batchId 为空则生成）；对同一 batchId 重复触发即幂等补投。 */
    public BatchPublishResult publish(BatchPublishRequest request) {
        validate(request);
        String batchId = request.batchId() == null || request.batchId().isBlank()
                ? UUID.randomUUID().toString()
                : request.batchId().trim();

        List<String> members = resolveMembers(request);
        if (members.isEmpty()) {
            throw new IllegalArgumentException(
                    "batch publish target set is empty: provide deviceIds or register devices for owner "
                            + request.ownerId());
        }
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        StateRequirements reqs = request.requirements() == null
                ? StateRequirements.none()
                : request.requirements();

        Batch existing = batches.get(batchId);
        if (existing != null) {
            verifyOwner(existing, request.ownerId());
            // 同一幂等键却携带不同配置/名单/要求：不允许悄悄套用旧批次，明确 409。
            if (!existing.sameContent(request.ownerId(), payload, reqs, members)) {
                log.warn("batch publish conflict: batchId={} reused with different payload/requirements/members",
                        batchId);
                throw new BatchAlreadyExistsException(batchId);
            }
            log.info("batch publish repeated trigger: batchId={} owner={} -> resume unfinished only",
                    batchId, request.ownerId());
            return existing.runAndSnapshot(false);
        }

        Batch created = new Batch(batchId, request.ownerId(), members, payload, reqs);
        Batch raced = batches.putIfAbsent(batchId, created);
        if (raced != null) {
            // 与并发首投竞争同一 batchId：只认先创建者，内容一致则退化为幂等补投，否则 409。
            verifyOwner(raced, request.ownerId());
            if (!raced.sameContent(request.ownerId(), payload, reqs, members)) {
                throw new BatchAlreadyExistsException(batchId);
            }
            log.info("batch publish raced creation, joining existing: batchId={}", batchId);
            return raced.runAndSnapshot(false);
        }
        log.info("batch publish created: batchId={} owner={} members={} payloadKeys={} requirements={}",
                batchId, request.ownerId(), members.size(), payload.size(), reqs);
        return created.runAndSnapshot(false);
    }

    /** 显式只补投未完成成员；语义与重复 publish 相同。 */
    public BatchPublishResult resume(String batchId, String ownerId) {
        Batch batch = requireBatch(batchId);
        verifyOwner(batch, ownerId);
        log.info("batch resume requested: batchId={} owner={}", batchId, ownerId);
        return batch.runAndSnapshot(true);
    }

    /** 查询一批发布当前的确定结果。 */
    public BatchPublishResult get(String batchId, String ownerId) {
        Batch batch = requireBatch(batchId);
        verifyOwner(batch, ownerId);
        return batch.snapshot(false);
    }

    // ---- request / member resolution ----

    private void validate(BatchPublishRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("batch publish request must not be null");
        }
        if (request.ownerId() == null || request.ownerId().isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
    }

    /** 冻结名单：显式 deviceIds 非空则用它；否则按 owner 圈定其名下全部设备。去重保序。 */
    private List<String> resolveMembers(BatchPublishRequest request) {
        Map<String, Boolean> unique = new LinkedHashMap<>();
        if (request.deviceIds() != null && !request.deviceIds().isEmpty()) {
            for (String id : request.deviceIds()) {
                if (id != null && !id.isBlank()) {
                    unique.putIfAbsent(id.trim(), Boolean.TRUE);
                }
            }
        } else {
            for (Device device : registry.all()) {
                if (request.ownerId().equals(device.getOwnerId())) {
                    unique.putIfAbsent(device.getDeviceId(), Boolean.TRUE);
                }
            }
        }
        return new ArrayList<>(unique.keySet());
    }

    private Batch requireBatch(String batchId) {
        Batch batch = batches.get(batchId);
        if (batch == null) {
            log.warn("batch access rejected: batchId={} not found", batchId);
            throw new BatchNotFoundException(batchId);
        }
        return batch;
    }

    private void verifyOwner(Batch batch, String ownerId) {
        if (ownerId == null || !batch.ownerId.equals(ownerId)) {
            log.warn("batch access rejected: batchId={} ownerMismatch batchOwner={} callerOwner={}",
                    batch.batchId, batch.ownerId, ownerId);
            throw new OwnerMismatchException(batch.batchId, String.valueOf(ownerId));
        }
    }

    // ---- batch aggregate ----

    /** 单批次的全部可变状态；所有触发在自身锁内串行，成员执行可并行。 */
    private final class Batch {

        private final String batchId;
        private final String ownerId;
        private final List<String> memberIds;
        private final Map<String, Object> payload;
        private final StateRequirements requirements;
        private final Map<String, Member> members = new LinkedHashMap<>();
        private final java.util.concurrent.locks.ReentrantLock runLock =
                new java.util.concurrent.locks.ReentrantLock();
        private volatile int runCount = 0;

        private Batch(String batchId, String ownerId, List<String> memberIds,
                      Map<String, Object> payload, StateRequirements requirements) {
            this.batchId = batchId;
            this.ownerId = ownerId;
            this.payload = payload;
            this.requirements = requirements;
            this.memberIds = memberIds;
            for (String id : memberIds) {
                members.put(id, new Member(id));
            }
        }

        private boolean sameContent(String owner, Map<String, Object> pl,
                                    StateRequirements reqs, List<String> resolvedMembers) {
            return this.ownerId.equals(owner)
                    && java.util.Objects.equals(this.payload, pl)
                    && java.util.Objects.equals(this.requirements, reqs)
                    && this.memberIds.equals(resolvedMembers);
        }

        private BatchPublishResult runAndSnapshot(boolean resumed) {
            runLock.lock();
            try {
                runCount++;
                int run = runCount;
                List<String> todo = new ArrayList<>();
                for (Member m : members.values()) {
                    synchronized (m) {
                        if (shouldAttempt(m)) {
                            todo.add(m.deviceId);
                        }
                    }
                }
                log.info("batch run start: batchId={} run={} total={} toAttempt={} resumed={}",
                        batchId, run, memberIds.size(), todo.size(), resumed || run > 1);

                if (!todo.isEmpty()) {
                    // 不同设备并行；每台设备的版本判定/送达仍在其设备账本锁内串行。
                    int parallelism = Math.min(todo.size(),
                            Math.max(2, Runtime.getRuntime().availableProcessors()));
                    ExecutorService pool = Executors.newFixedThreadPool(parallelism);
                    try {
                        List<Future<?>> futures = new ArrayList<>();
                        for (String deviceId : todo) {
                            futures.add(pool.submit(() -> processMember(deviceId, run)));
                        }
                        for (Future<?> f : futures) {
                            try {
                                f.get();
                            } catch (Exception e) {
                                // 单台异常不得拖垮整批：记为执行失败，其它成员结果不受影响。
                                log.error("batch member processing failed unexpectedly: batchId={} error={}",
                                        batchId, e.toString());
                            }
                        }
                    } finally {
                        pool.shutdown();
                    }
                }
                return snapshot(resumed || run > 1);
            } finally {
                runLock.unlock();
            }
        }

        /**
         * 判定某成员本轮是否需要实际处理（成员锁内读取，保证与后续 record 一致）：
         * 未做过 -> 尝试；仍 PENDING 失败 -> 补投；已成功/已落定/被不兼容永久挡下 -> 跳过。
         */
        private boolean shouldAttempt(Member m) {
            synchronized (m) {
                if (m.incompatibleBlocked) {
                    return false;
                }
                BatchMemberOutcome o = m.outcome;
                if (o == null) {
                    return true;
                }
                return switch (o) {
                    case NOT_ATTEMPTED, NOT_DELIVERED, REJECTED_BY_DEVICE, EXECUTION_FAILED,
                            DEVICE_NOT_FOUND, OWNER_MISMATCH -> true;
                    default -> false;
                };
            }
        }

        private void processMember(String deviceId, int run) {
            Member member = members.get(deviceId);
            try {
                doProcessMember(deviceId, run, member);
            } catch (RuntimeException e) {
                // 任何未预期异常都局限在该成员：落为执行失败（下一轮补投可再试），不拖垮整批。
                member.record(BatchMemberOutcome.EXECUTION_FAILED, member.currentRevision(),
                        "unexpected error while processing member: " + e,
                        member.isBlocked(), run);
                log.error("batch member processing error: batchId={} run={} deviceId={} error={}",
                        batchId, run, deviceId, e.toString());
            }
        }

        private void doProcessMember(String deviceId, int run, Member member) {
            // 1) 先按当时归属方与存在性逐台解析（404 与 403 严格区分）。
            Device device = registry.find(deviceId);
            if (device == null) {
                member.record(BatchMemberOutcome.DEVICE_NOT_FOUND, member.currentRevision(),
                        "device does not exist: " + deviceId, false, run);
                log.warn("batch member DEVICE_NOT_FOUND: batchId={} run={} deviceId={}",
                        batchId, run, deviceId);
                return;
            }
            if (!ownerId.equals(device.getOwnerId())) {
                member.record(BatchMemberOutcome.OWNER_MISMATCH, member.currentRevision(),
                        "device belongs to owner " + device.getOwnerId()
                                + " but batch owner is " + ownerId, false, run);
                log.warn("batch member OWNER_MISMATCH: batchId={} run={} deviceId={} deviceOwner={} callerOwner={}",
                        batchId, run, deviceId, device.getOwnerId(), ownerId);
                return;
            }

            long assignedRevision = member.currentRevision();
            if (assignedRevision == 0L) {
                // 2) 首次实际处理：在设备锁内原子判定“已最新 / 不兼容 / 创建新版本并送达”。
                OrchestrationService.BatchAttempt attempt =
                        orchestration.attemptForBatch(device, payload, requirements);
                BatchMemberOutcome mapped = mapOutcome(attempt.outcome());
                boolean blocked = attempt.outcome() == DeliveryOutcome.REJECTED_INCOMPATIBLE;
                member.record(mapped, attempt.revision(), attempt.detail(), blocked, run);
                log.info("batch member first attempt: batchId={} run={} deviceId={} firmware={} capabilities={} revision={} outcome={} blocked={} detail={}",
                        batchId, run, deviceId, device.getFirmwareVersion(),
                        device.getCapabilities(), attempt.revision(), mapped, blocked,
                        attempt.detail());
                return;
            }

            // 3) 补投未完成：只重送该成员先前分配的同一 revision，不产生新版本。
            OrchestrationService.BatchAttempt attempt =
                    orchestration.retryForBatch(device, assignedRevision);
            BatchMemberOutcome mapped = mapOutcome(attempt.outcome());
            log.info("batch member retry: batchId={} run={} deviceId={} revision={} outcome={} detail={}",
                    batchId, run, deviceId, assignedRevision, mapped, attempt.detail());
            member.record(mapped, attempt.revision(), attempt.detail(),
                    member.isBlocked(), run);
        }

        private BatchMemberOutcome mapOutcome(DeliveryOutcome outcome) {
            return switch (outcome) {
                case APPLIED -> BatchMemberOutcome.APPLIED;
                case ALREADY_APPLIED -> BatchMemberOutcome.UP_TO_DATE;
                case NOT_DELIVERED -> BatchMemberOutcome.NOT_DELIVERED;
                case REJECTED_INCOMPATIBLE -> BatchMemberOutcome.REJECTED_INCOMPATIBLE;
                case REJECTED_BY_DEVICE -> BatchMemberOutcome.REJECTED_BY_DEVICE;
                case EXECUTION_FAILED -> BatchMemberOutcome.EXECUTION_FAILED;
                case REVOKED -> BatchMemberOutcome.SKIPPED_SETTLED;
                case SUPERSEDED -> BatchMemberOutcome.SKIPPED_SETTLED;
                default -> BatchMemberOutcome.EXECUTION_FAILED;
            };
        }

        private BatchPublishResult snapshot(boolean resumed) {
            List<BatchMemberResult> results = new ArrayList<>();
            int applied = 0, alreadyApplied = 0, upToDate = 0, notDelivered = 0;
            int rejectedIncompatible = 0, rejectedByDevice = 0, executionFailed = 0;
            int deviceNotFound = 0, ownerMismatch = 0, skippedSettled = 0, skippedBlocked = 0;
            for (Member m : members.values()) {
                BatchMemberOutcome outcome;
                long revision;
                String detail;
                boolean blocked;
                synchronized (m) {
                    boolean processedThisRun = m.lastRun == runCount;
                    // 被不兼容永久挡下的成员：首次挡下的那一轮如实给出 REJECTED_INCOMPATIBLE；
                    // 之后任何补投/查询一律展示 SKIPPED_BLOCKED，不随上线/固件变化复活。
                    if (m.incompatibleBlocked && !processedThisRun) {
                        outcome = BatchMemberOutcome.SKIPPED_BLOCKED;
                    } else if (!m.incompatibleBlocked && m.previouslyApplied && !processedThisRun) {
                        // 之前轮次已成功、本轮未再触碰：本来就是最新，无需重发。
                        outcome = BatchMemberOutcome.UP_TO_DATE;
                    } else {
                        outcome = m.outcome == null ? BatchMemberOutcome.NOT_ATTEMPTED : m.outcome;
                    }
                    revision = m.revision;
                    detail = m.detail;
                    blocked = m.incompatibleBlocked;
                }
                // 汇总按“当前持久结论”：不兼容首投计入 rejectedIncompatible；
                // 之后被永久挡下且本轮跳过的计入 skippedBlocked。
                if (blocked && outcome == BatchMemberOutcome.SKIPPED_BLOCKED) {
                    skippedBlocked++;
                } else {
                    switch (outcome) {
                        case APPLIED -> applied++;
                        case ALREADY_APPLIED -> alreadyApplied++;
                        case UP_TO_DATE -> upToDate++;
                        case NOT_DELIVERED -> notDelivered++;
                        case REJECTED_INCOMPATIBLE -> rejectedIncompatible++;
                        case REJECTED_BY_DEVICE -> rejectedByDevice++;
                        case EXECUTION_FAILED -> executionFailed++;
                        case DEVICE_NOT_FOUND -> deviceNotFound++;
                        case OWNER_MISMATCH -> ownerMismatch++;
                        case SKIPPED_SETTLED -> skippedSettled++;
                        case SKIPPED_BLOCKED -> skippedBlocked++;
                        default -> { }
                    }
                }
                results.add(new BatchMemberResult(m.deviceId, outcome, revision, detail, blocked));
            }
            BatchSummary summary = new BatchSummary(memberIds.size(), applied, alreadyApplied,
                    upToDate, notDelivered, rejectedIncompatible, rejectedByDevice,
                    executionFailed, deviceNotFound, ownerMismatch, skippedSettled, skippedBlocked);
            log.info("batch snapshot: batchId={} runs={} resumed={} summary={}",
                    batchId, runCount, resumed, summary);
            return new BatchPublishResult(batchId, ownerId, resumed,
                    List.copyOf(results), summary);
        }
    }

    /** 单成员的持久结论（所有字段仅在成员监视器内读写）。 */
    private static final class Member {

        private final String deviceId;
        private BatchMemberOutcome outcome = BatchMemberOutcome.NOT_ATTEMPTED;
        private long revision;
        private String detail = "not attempted yet";
        private boolean incompatibleBlocked;
        private boolean previouslyApplied;
        private int lastRun;

        private Member(String deviceId) {
            this.deviceId = deviceId;
        }

        private synchronized long currentRevision() {
            return revision;
        }

        private synchronized boolean isBlocked() {
            return incompatibleBlocked;
        }

        /**
         * 记录本轮结论。幂等保护：同一 run 内（并发/乱序重复执行）成员已落到 APPLIED 且
         * revision 相同，则后到的 UP_TO_DATE 不得覆盖它——成功只承认第一次。
         */
        private synchronized void record(BatchMemberOutcome newOutcome, long newRevision,
                                         String newDetail, boolean blocked, int run) {
            if (run == lastRun && revision == newRevision && revision != 0L
                    && outcome == BatchMemberOutcome.APPLIED
                    && newOutcome == BatchMemberOutcome.UP_TO_DATE) {
                return;
            }
            this.outcome = newOutcome;
            this.revision = newRevision;
            this.detail = newDetail;
            if (blocked) {
                this.incompatibleBlocked = true;
            }
            if (newOutcome == BatchMemberOutcome.APPLIED) {
                this.previouslyApplied = true;
            }
            this.lastRun = run;
        }
    }
}
