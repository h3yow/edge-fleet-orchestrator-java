package com.github.h3yow.edgefleetorchestratorjava.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 一条期望状态修订：单调递增的版本号、配置内容与生命周期状态。
 *
 * <p>状态迁移规则（终态不可回退；已撤销变更不得被重新应用）：
 * <pre>
 *   PENDING ──► APPLIED / REJECTED / FAILED / SUPERSEDED / REVOKED / NOT_DELIVERED(保持 PENDING)
 *   其余状态均为终态。
 * </pre>
 * 状态迁移必须在设备锁内完成（由 FleetService 保证同一设备串行化）。
 */
public final class DesiredRevision {

    private static final Set<RevisionState> TERMINAL = Set.of(
            RevisionState.APPLIED, RevisionState.REJECTED, RevisionState.FAILED,
            RevisionState.SUPERSEDED, RevisionState.REVOKED);

    private final long revision;
    private final String owner;
    private final Map<String, String> config;
    private final Instant createdAt;
    private RevisionState state;
    private Instant decidedAt;
    private DispatchOutcome outcome;
    private String detail;

    public DesiredRevision(long revision, String owner, Map<String, String> config, Instant createdAt) {
        this.revision = revision;
        this.owner = owner;
        this.config = Map.copyOf(config);
        this.createdAt = createdAt;
        this.state = RevisionState.PENDING;
    }

    public long revision() { return revision; }
    public String owner() { return owner; }
    public Map<String, String> config() { return config; }
    public Instant createdAt() { return createdAt; }
    public RevisionState state() { return state; }
    public Instant decidedAt() { return decidedAt; }
    public DispatchOutcome outcome() { return outcome; }
    public String detail() { return detail; }

    public boolean isTerminal() {
        return TERMINAL.contains(state);
    }

    /**
     * 在允许的迁移上推进修订状态；NOT_DELIVERED 不是终态，修订保持 PENDING 以便重试。
     *
     * @throws IllegalStateException 当修订已处于终态却被要求迁移时（防止已撤销变更被重新应用）
     */
    public void transition(RevisionState newState, DispatchOutcome outcome, Instant at, String detail) {
        if (newState == RevisionState.PENDING) {
            this.outcome = outcome;
            this.detail = detail;
            return;
        }
        if (isTerminal()) {
            throw new IllegalStateException("revision " + revision + " is already terminal in state "
                    + state + "; cannot move to " + newState);
        }
        this.state = newState;
        this.outcome = outcome;
        this.decidedAt = at;
        this.detail = detail;
    }
}
