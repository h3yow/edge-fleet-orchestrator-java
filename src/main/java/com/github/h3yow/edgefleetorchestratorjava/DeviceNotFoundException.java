package com.github.h3yow.edgefleetorchestratorjava;

/** 目标设备不存在，与"归属方不匹配"等拒绝情形可区分。 */
public class DeviceNotFoundException extends RuntimeException {
    public DeviceNotFoundException(String deviceId) {
        super("device not found: " + deviceId);
    }
}
