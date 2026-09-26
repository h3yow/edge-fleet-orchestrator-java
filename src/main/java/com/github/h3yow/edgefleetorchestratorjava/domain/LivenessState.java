package com.github.h3yow.edgefleetorchestratorjava.domain;

/**
 * 设备存活状态（由最近一次被接受的上报时间与 TTL 实时推导）。
 */
public enum LivenessState {
    ONLINE,
    OFFLINE
}
