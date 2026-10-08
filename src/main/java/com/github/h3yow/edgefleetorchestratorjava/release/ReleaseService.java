package com.github.h3yow.edgefleetorchestratorjava.release;

import com.github.h3yow.edgefleetorchestratorjava.DeviceNotFoundException;
import com.github.h3yow.edgefleetorchestratorjava.OwnerMismatchException;
import com.github.h3yow.edgefleetorchestratorjava.model.DeliveryOutcome;
import com.github.h3yow.edgefleetorchestratorjava.model.DispatchRequest;
import com.github.h3yow.edgefleetorchestratorjava.model.DispatchResult;
import com.github.h3yow.edgefleetorchestratorjava.model.ReleaseMemberResult;
import com.github.h3yow.edgefleetorchestratorjava.model.ReleaseRequest;
import com.github.h3yow.edgefleetorchestratorjava.model.ReleaseResult;
import com.github.h3yow.edgefleetorchestratorjava.model.ReleaseSummary;
import com.github.h3yow.edgefleetorchestratorjava.model.StateRequirements;
import com.github.h3yow.edgefleetorchestratorjava.orchestration.OrchestrationService;
import com.github.h3yow.edgefleetorchestratorjava.registry.DeviceRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 面向一批设备的发布服务。
 *
 * <p>模型：一批发布（{@link DeviceRelease}）持有同一份载荷/兼容要求与逐台成员记录；
 * 每台设备在首批触发时经 {@link OrchestrationService#dispatch} 获得各自单调递增的
 * revision —— 批次与设备版本是"一对多"关系，批次本身不引入额外版本号。
 *
 * <p>补投与幂等：
 * <ol>
 *   <li>releaseId 是幂等键。重复提交（手抖/网络重发）命中同一批，只补投未完成成员，
 *       不创建新版本、不重复应用；</li>
 *   <li>补投经 {@link OrchestrationService#retryRevision} 重投成员各自已登记的 revision，
 *       成功（APPLIED/ALREADY_APPLIED）与终态（SUPERSEDED/REVOKED）成员不再触碰；</li>
 *   <li>REJECTED_INCOMPATIBLE 成员永久挡下：即使设备重新上线或固件/能力变化，
 *       也不会被补投生效，只能由归属方发起新一批发布；</li>
 *   <li>单台失败不影响同批其它设备；个别设备连不上不会把整批算作失败。</li>
 * </ol>
 *
 * <p>归属与兼容：逐台下发前按设备当时的归属方与固件/能力判定（复用单机路径），
 * 越权成员记 OWNER_MISMATCH、不存在记 DEVICE_NOT_FOUND，二者明确区分且不放行。
 */
@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    private final DeviceRegistry registry;
    private final OrchestrationService orchestration;
    private final Clock clock;
    private final Map<String, DeviceRelease> releases = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    @org.springframework.beans.factory.annotation.Autowired
    public ReleaseService(DeviceRegistry registry, OrchestrationService orchestration, Clock clock) {
        this(registry, orchestration, clock,
                Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
    }

    /** 测试可注入自定义执行器。 */
    public ReleaseService(DeviceRegistry registry, OrchestrationService orchestration,
                          Clock clock, ExecutorService executor) {
        this.registry = registry;
        this.orchestration = orchestration;
        this.clock = clock;
        this.executor = executor;
    }

    /**
     * 提交一批发布。首次触发创建批次并逐台下发；同 releaseId 重复触发等价于
     * {@link #retryIncomplete}，最终结果唯一确定。
     */
    public ReleaseResult submit(ReleaseRequest request) {
        validate(request);
        StateRequirements requirements = request.requirements() == null
                ? StateRequirements.none()
                : request.requirements();
        DeviceRelease release = releases.computeIfAbsent(request.releaseId(),
                id -> new DeviceRelease(id, request.ownerId(), request.payload(),
                        requirements, clock.instant()));
        release.lock().lock();
        try {
            if (!release.ownerId().equals(request.ownerId())) {
                log.warn("release submit rejected: releaseId={} ownerMismatch releaseOwner={} callerOwner={}",
                        release.releaseId(), release.ownerId(), request.ownerId());
                throw new OwnerMismatchException(release.releaseId(), String.valueOf(request.ownerId()));
            }
            if (!Objects.equals(release.payload(), request.payload())
                    || !Objects.equals(release.requirements(), requirements)) {
                log.warn("release submit conflict: releaseId={} already exists with different spec",
                        release.releaseId());
                throw new ReleaseConflictException(release.releaseId());
            }
            if (!release.initialized) {
                initializeTargets(release, request);
                dispatchAll(release);
                release.initialized = true;
            } else {
                log.info("release re-triggered: releaseId={} -> retry incomplete members only",
                        release.releaseId());
                retryIncompleteMembers(release);
            }
            return snapshot(release);
        } finally {
            release.lock().unlock();
        }
    }

    /** 只补投未完成成员；与重复 submit 同语义，幂等。 */
    public ReleaseResult retryIncomplete(String releaseId, String ownerId) {
        DeviceRelease release = releases.get(releaseId);
        if (release == null) {
            log.warn("release retry rejected: releaseId={} not found (callerOwner={})",
                    releaseId, ownerId);
            throw new DeviceNotFoundException("release " + releaseId);
        }
        release.lock().lock();
        try {
            if (!release.ownerId().equals(ownerId)) {
                log.warn("release retry rejected: releaseId={} ownerMismatch releaseOwner={} callerOwner={}",
                        releaseId, release.ownerId(), ownerId);
                throw new OwnerMismatchException(releaseId, String.valueOf(ownerId));
            }
            retryIncompleteMembers(release);
            return snapshot(release);
        } finally {
            release.lock().unlock();
        }
    }

    /** 查询一批发布的当前结论（逐台 + 汇总）。 */
    public ReleaseResult get(String releaseId, String ownerId) {
        DeviceRelease release = releases.get(releaseId);
        if (release == null) {
            throw new DeviceNotFoundException("release " + releaseId);
        }
        release.lock().lock();
        try {
            if (!release.ownerId().equals(ownerId)) {
                throw new OwnerMismatchException(releaseId, String.valueOf(ownerId));
            }
            return snapshot(release);
        } finally {
            release.lock().unlock();
        }
    }

    // ---- internals ----

    @jakarta.annotation.PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private void validate(ReleaseRequest request) {
        if (request.releaseId() == null || request.releaseId().isBlank()) {
            throw new IllegalArgumentException("releaseId must not be blank");
        }
        if (request.ownerId() == null || request.ownerId().isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
    }

    /** 解析目标设备：显式列表（去重、保序）或按归属方圈定。 */
    private void initializeTargets(DeviceRelease release, ReleaseRequest request) {
        List<String> targets;
        if (request.deviceIds() != null && !request.deviceIds().isEmpty()) {
            targets = new ArrayList<>(new LinkedHashSet<>(request.deviceIds()));
        } else {
            targets = registry.all().stream()
                    .filter(d -> d.getOwnerId().equals(release.ownerId()))
                    .map(d -> d.getDeviceId())
                    .sorted()
                    .toList();
        }
        for (String deviceId : targets) {
            release.members().put(deviceId, new DeviceRelease.Member(deviceId));
        }
        log.info("release initialized: releaseId={} owner={} targets={}",
                release.releaseId(), release.ownerId(), targets);
    }

    /** 首批下发：逐台并行，各自创建单调 revision；单台异常只影响本台结论。 */
    private void dispatchAll(DeviceRelease release) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (DeviceRelease.Member member : release.members().values()) {
            futures.add(CompletableFuture.runAsync(() -> dispatchOne(release, member), executor));
        }
        futures.forEach(CompletableFuture::join);
    }

    private void dispatchOne(DeviceRelease release, DeviceRelease.Member member) {
        try {
            DispatchResult result = orchestration.dispatch(new DispatchRequest(
                    member.deviceId, release.ownerId(), release.payload(), release.requirements()));
            member.revision = result.requestedRevision();
            member.outcome = result.outcome();
            member.detail = result.detail();
        } catch (DeviceNotFoundException e) {
            member.outcome = DeliveryOutcome.DEVICE_NOT_FOUND;
            member.detail = e.getMessage();
        } catch (OwnerMismatchException e) {
            member.outcome = DeliveryOutcome.OWNER_MISMATCH;
            member.detail = e.getMessage();
        }
        log.info("release member dispatched: releaseId={} deviceId={} revision={} outcome={} detail={}",
                release.releaseId(), member.deviceId, member.revision, member.outcome, member.detail);
    }

    /**
     * 只补投未完成成员：未送达/设备拒绝/执行失败三类可补；
     * 成功、终态、不兼容、越权/不存在成员一律不再触碰。
     */
    private void retryIncompleteMembers(DeviceRelease release) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (DeviceRelease.Member member : release.members().values()) {
            DeliveryOutcome outcome = member.outcome;
            if (outcome == null || !isRetryable(outcome)) {
                log.info("release member skipped on retry: releaseId={} deviceId={} outcome={} (terminal or settled)",
                        release.releaseId(), member.deviceId, outcome);
                continue;
            }
            futures.add(CompletableFuture.runAsync(() -> retryOne(release, member), executor));
        }
        futures.forEach(CompletableFuture::join);
    }

    private boolean isRetryable(DeliveryOutcome outcome) {
        return switch (outcome) {
            case NOT_DELIVERED, REJECTED_BY_DEVICE, EXECUTION_FAILED -> true;
            default -> false;
        };
    }

    private void retryOne(DeviceRelease release, DeviceRelease.Member member) {
        try {
            DispatchResult result = orchestration.retryRevision(
                    member.deviceId, release.ownerId(), member.revision);
            member.outcome = result.outcome();
            member.detail = result.detail();
        } catch (DeviceNotFoundException e) {
            member.outcome = DeliveryOutcome.DEVICE_NOT_FOUND;
            member.detail = e.getMessage();
        } catch (OwnerMismatchException e) {
            member.outcome = DeliveryOutcome.OWNER_MISMATCH;
            member.detail = e.getMessage();
        }
        log.info("release member retried: releaseId={} deviceId={} revision={} outcome={} detail={}",
                release.releaseId(), member.deviceId, member.revision, member.outcome, member.detail);
    }

    private ReleaseResult snapshot(DeviceRelease release) {
        List<ReleaseMemberResult> members = new ArrayList<>();
        for (DeviceRelease.Member member : release.members().values()) {
            members.add(new ReleaseMemberResult(member.deviceId, member.outcome,
                    member.revision, member.detail));
        }
        return new ReleaseResult(release.releaseId(), release.ownerId(), members, summarize(members));
    }

    private ReleaseSummary summarize(List<ReleaseMemberResult> members) {
        int applied = 0, already = 0, notDelivered = 0, incompatible = 0,
                rejected = 0, failed = 0, ownerMismatch = 0, notFound = 0,
                superseded = 0, revoked = 0;
        for (ReleaseMemberResult m : members) {
            switch (m.outcome()) {
                case APPLIED -> applied++;
                case ALREADY_APPLIED -> already++;
                case NOT_DELIVERED -> notDelivered++;
                case REJECTED_INCOMPATIBLE -> incompatible++;
                case REJECTED_BY_DEVICE -> rejected++;
                case EXECUTION_FAILED -> failed++;
                case OWNER_MISMATCH -> ownerMismatch++;
                case DEVICE_NOT_FOUND -> notFound++;
                case SUPERSEDED -> superseded++;
                case REVOKED -> revoked++;
                default -> { }
            }
        }
        return new ReleaseSummary(members.size(), applied, already, notDelivered, incompatible,
                rejected, failed, ownerMismatch, notFound, superseded, revoked);
    }
}
