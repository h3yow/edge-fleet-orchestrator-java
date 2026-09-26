package com.github.h3yow.edgefleetorchestratorjava.session;

import com.github.h3yow.edgefleetorchestratorjava.model.DesiredState;

/**
 * 与单台边缘设备的本地会话（模拟真实网关链路）。
 * 同一 revision 重复 apply 必须保持幂等，不会被反复应用。
 */
public interface DeviceSession {

    String deviceId();

    SessionResponse apply(DesiredState state);

    boolean isReachable();
}
