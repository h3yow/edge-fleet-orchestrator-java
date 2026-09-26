package com.github.h3yow.edgefleetorchestratorjava.orchestration;

import com.github.h3yow.edgefleetorchestratorjava.model.DesiredState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单台设备的期望状态账本。
 *
 * <p>每个设备一把 {@link ReentrantLock}：同一设备的下发/重试/撤销在此串行化，
 * 保证并发触发时最终收敛状态唯一确定；不同设备之间互不阻塞。
 * 账本保留全部历史版本，当前 PENDING 版本与最新版本均可追溯。
 */
final class DeviceStateLedger {

    private final ReentrantLock lock = new ReentrantLock();
    private final List<DesiredState> revisions = new ArrayList<>();

    ReentrantLock lock() {
        return lock;
    }

    long nextRevision() {
        return revisions.size() + 1L;
    }

    void append(DesiredState state) {
        revisions.add(state);
    }

    /** 最新版本（revision 最大），无则 null。 */
    DesiredState latest() {
        return revisions.isEmpty() ? null : revisions.get(revisions.size() - 1);
    }

    /** 当前仍处于 PENDING 的最新版本；已应用/撤销/取代时返回 null。 */
    DesiredState pending() {
        DesiredState latest = latest();
        if (latest != null && latest.getPhase()
                == com.github.h3yow.edgefleetorchestratorjava.model.StatePhase.PENDING) {
            return latest;
        }
        return null;
    }

    int revisionCount() {
        return revisions.size();
    }
}
