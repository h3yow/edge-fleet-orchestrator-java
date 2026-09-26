package com.github.h3yow.edgefleetorchestratorjava.model;

import java.time.Instant;
import java.util.Set;

/** 已注册设备及其最近一次被接受的上报轨迹。 */
public final class Device {

    private final String deviceId;
    private final String ownerId;
    private final String firmwareVersion;
    private final Set<String> capabilities;
    private final Instant registeredAt;

    private volatile DeviceStatus status = DeviceStatus.OFFLINE;
    private volatile Instant lastSeenAt;
    private volatile Instant lastReportTimestamp;

    public Device(String deviceId, String ownerId, String firmwareVersion,
                  Set<String> capabilities, Instant registeredAt) {
        this.deviceId = deviceId;
        this.ownerId = ownerId;
        this.firmwareVersion = firmwareVersion;
        this.capabilities = capabilities;
        this.registeredAt = registeredAt;
    }

    public String getDeviceId() { return deviceId; }
    public String getOwnerId() { return ownerId; }
    public String getFirmwareVersion() { return firmwareVersion; }
    public Set<String> getCapabilities() { return capabilities; }
    public Instant getRegisteredAt() { return registeredAt; }
    public DeviceStatus getStatus() { return status; }
    public void setStatus(DeviceStatus status) { this.status = status; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getLastReportTimestamp() { return lastReportTimestamp; }
    public void setLastReportTimestamp(Instant lastReportTimestamp) { this.lastReportTimestamp = lastReportTimestamp; }
}
