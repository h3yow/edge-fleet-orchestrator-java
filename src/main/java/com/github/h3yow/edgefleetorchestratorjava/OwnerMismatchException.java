package com.github.h3yow.edgefleetorchestratorjava;

/** 请求归属方与设备归属方不一致（越权 / 跨归属方访问）。 */
public class OwnerMismatchException extends RuntimeException {
    public OwnerMismatchException(String deviceId, String callerOwner) {
        super("owner mismatch for device " + deviceId + " from " + callerOwner);
    }
}
