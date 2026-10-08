package com.github.h3yow.edgefleetorchestratorjava.release;

import com.github.h3yow.edgefleetorchestratorjava.model.DeliveryOutcome;
import com.github.h3yow.edgefleetorchestratorjava.model.StateRequirements;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一批发布的内部状态（内存实现）。
 *
 * <p>一批发布 = releaseId + 归属方 + 同一份载荷/兼容要求 + 逐台成员记录。
 * 成员记录保存该设备在本批中对应的 revision 与最新结论；补投只依据成员记录推进，
 * 不会创建新 revision。每批一把锁，重复触发（手抖/网络重发）在锁内串行，结果唯一确定。
 */
final class DeviceRelease {

    /** 单台设备在一批发布中的轨迹。 */
    static final class Member {
        final String deviceId;
        /** 本批为该设备创建的 revision；越权/不存在等未创建版本时为 0。 */
        volatile long revision;
        volatile DeliveryOutcome outcome;
        volatile String detail;

        Member(String deviceId) {
            this.deviceId = deviceId;
        }
    }

    private final String releaseId;
    private final String ownerId;
    private final Map<String, Object> payload;
    private final StateRequirements requirements;
    private final Instant createdAt;
    private final ReentrantLock lock = new ReentrantLock();
    /** 成员表：deviceId -> Member，保持目标顺序便于阅读。 */
    private final Map<String, Member> members = new LinkedHashMap<>();
    /** 首批目标是否已完成首次下发；重复触发时据此走"只补投"路径。 */
    volatile boolean initialized;

    DeviceRelease(String releaseId, String ownerId, Map<String, Object> payload,
                  StateRequirements requirements, Instant createdAt) {
        this.releaseId = releaseId;
        this.ownerId = ownerId;
        this.payload = payload;
        this.requirements = requirements;
        this.createdAt = createdAt;
    }

    String releaseId() { return releaseId; }
    String ownerId() { return ownerId; }
    Map<String, Object> payload() { return payload; }
    StateRequirements requirements() { return requirements; }
    Instant createdAt() { return createdAt; }
    ReentrantLock lock() { return lock; }
    Map<String, Member> members() { return members; }
}
