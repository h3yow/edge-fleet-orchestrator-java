package com.github.h3yow.edgefleetorchestratorjava.model;

import java.time.Instant;
import java.util.Map;

/** 某一版本（revision）的期望状态及其收敛轨迹。 */
public final class DesiredState {

    private final long revision;
    private final Map<String, Object> payload;
    private final StateRequirements requirements;
    private final Instant createdAt;

    private volatile StatePhase phase = StatePhase.PENDING;
    private volatile Instant appliedAt;
    private volatile DeliveryOutcome lastOutcome;
    private volatile String lastDetail;

    public DesiredState(long revision, Map<String, Object> payload,
                        StateRequirements requirements, Instant createdAt) {
        this.revision = revision;
        this.payload = payload;
        this.requirements = requirements;
        this.createdAt = createdAt;
    }

    public long getRevision() { return revision; }
    public Map<String, Object> getPayload() { return payload; }
    public StateRequirements getRequirements() { return requirements; }
    public Instant getCreatedAt() { return createdAt; }
    public StatePhase getPhase() { return phase; }
    public void setPhase(StatePhase phase) { this.phase = phase; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }
    public DeliveryOutcome getLastOutcome() { return lastOutcome; }
    public void setLastOutcome(DeliveryOutcome lastOutcome) { this.lastOutcome = lastOutcome; }
    public String getLastDetail() { return lastDetail; }
    public void setLastDetail(String lastDetail) { this.lastDetail = lastDetail; }
}
