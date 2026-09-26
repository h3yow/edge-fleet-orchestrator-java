package com.github.h3yow.edgefleetorchestratorjava.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 设备聚合：注册信息、能力信息、存活心跳与期望状态修订链。
 */
public final class Device {

    private final String id;
    private final String owner;
    private Version firmware;
    private Set<String> capabilities;
    private Instant registeredAt;
    private Instant lastAcceptedReportAt;
    private long lastAcceptedSeq;
    private Duration ttl;
    private long revisionSeq;
    private final Map<Long, DesiredRevision> revisions = new LinkedHashMap<>();

    public Device(String id, String owner, Version firmware, Set<String> capabilities,
                  Instant registeredAt, Duration ttl) {
        this.id = id;
        this.owner = owner;
        this.firmware = firmware;
        this.capabilities = new HashSet<>(capabilities);
        this.registeredAt = registeredAt;
        this.ttl = ttl;
        this.lastAcceptedSeq = -1L;
        this.revisionSeq = 0L;
    }

    public String id() { return id; }
    public String owner() { return owner; }
    public Version firmware() { return firmware; }
    public Set<String> capabilities() { return capabilities; }
    public Instant registeredAt() { return registeredAt; }
    public Instant lastAcceptedReportAt() { return lastAcceptedReportAt; }
    public long lastAcceptedSeq() { return lastAcceptedSeq; }
    public Duration ttl() { return ttl; }
    public long revisionSeq() { return revisionSeq; }
    public Map<Long, DesiredRevision> revisions() { return revisions; }

    public void setFirmware(Version firmware) { this.firmware = firmware; }
    public void setCapabilities(Set<String> capabilities) { this.capabilities = new HashSet<>(capabilities); }
    public void setLastAcceptedReportAt(Instant at) { this.lastAcceptedReportAt = at; }
    public void setLastAcceptedSeq(long seq) { this.lastAcceptedSeq = seq; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public long nextRevisionId() { return ++revisionSeq; }

    /**
     * @return 修订号最大的一条修订（无论状态），不存在时返回 null
     */
    public DesiredRevision latestRevision() {
        DesiredRevision latest = null;
        for (DesiredRevision r : revisions.values()) {
            if (latest == null || r.revision() > latest.revision()) {
                latest = r;
            }
        }
        return latest;
    }

    public DesiredRevision revision(long id) {
        return revisions.get(id);
    }

    /**
     * 存活状态实时推导：从未上报，或距最近一次被接受的上报超过 TTL，即 OFFLINE。
     * 被拒绝/被忽略的上报不会更新 lastAcceptedReportAt，因此无法续命。
     */
    public LivenessState livenessAt(Instant now) {
        if (lastAcceptedReportAt == null) {
            return LivenessState.OFFLINE;
        }
        return lastAcceptedReportAt.plus(ttl).isAfter(now)
                ? LivenessState.ONLINE
                : LivenessState.OFFLINE;
    }
}
