package com.github.h3yow.edgefleetorchestratorjava.session;

import com.github.h3yow.edgefleetorchestratorjava.model.ReportResult;
import com.github.h3yow.edgefleetorchestratorjava.report.ReportIngestService;
import java.time.Instant;
import java.util.Map;

/**
 * 设备 -> 平台的模拟上报通道（设备侧本地客户端）。
 *
 * <p>对应 {@link DeviceSession} 的反向链路：设备端不直接调用服务层，
 * 而是通过本通道把"带设备时钟时间戳的上报"送入 {@link ReportIngestService}，
 * 用于在测试/演示中模拟真实网关的上报入口。
 */
public record ReportChannel(String deviceId, Instant timestamp, Map<String, Object> metrics) {

    public static ReportChannel heartbeat(String deviceId, Instant timestamp) {
        return new ReportChannel(deviceId, timestamp, Map.of());
    }

    public ReportResult send(ReportIngestService ingest) {
        return ingest.ingest(deviceId, timestamp, metrics);
    }
}
