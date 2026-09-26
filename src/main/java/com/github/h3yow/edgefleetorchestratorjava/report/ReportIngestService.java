package com.github.h3yow.edgefleetorchestratorjava.report;

import com.github.h3yow.edgefleetorchestratorjava.DeviceNotFoundException;
import com.github.h3yow.edgefleetorchestratorjava.model.Device;
import com.github.h3yow.edgefleetorchestratorjava.model.DeviceStatus;
import com.github.h3yow.edgefleetorchestratorjava.model.ReportResult;
import com.github.h3yow.edgefleetorchestratorjava.model.ReportVerdict;
import com.github.h3yow.edgefleetorchestratorjava.registry.DeviceRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 上报受理服务。
 *
 * <p>对设备周期上报执行严格的时间戳判定：
 * <ul>
 *   <li>{@code null} 时间戳 -> REJECTED_MISSING_TIMESTAMP（拒绝）；</li>
 *   <li>超过未来容忍窗口 -> REJECTED_FUTURE_TIMESTAMP（拒绝，时钟异常不可信）；</li>
 *   <li>时间戳等于上一次被接受的上报 -> IGNORED_DUPLICATE（重复，忽略）；</li>
 *   <li>时间戳早于上一次被接受的上报 -> IGNORED_STALE_TIMESTAMP（乱序/回退，忽略，
 *       不得覆盖更新状态）；</li>
 *   <li>其余 -> ACCEPTED，刷新存活轨迹。</li>
 * </ul>
 * 拒绝与忽略都<b>不会</b>更新 lastSeen / lastReportTimestamp，设备不会因此无限期保持可用
 * （过期仍由 {@link LivenessService} 置为 OFFLINE）。
 */
@Service
public class ReportIngestService {

    private static final Logger log = LoggerFactory.getLogger(ReportIngestService.class);

    /** 允许的设备时钟超前窗口，超过即判定为不可信的未来时间戳。 */
    private static final Duration FUTURE_TOLERANCE = Duration.ofSeconds(5);

    private final DeviceRegistry registry;
    private final Clock clock;

    public ReportIngestService(DeviceRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    public ReportResult ingest(String deviceId, Instant timestamp, Map<String, Object> metrics) {
        Device device = registry.find(deviceId);
        if (device == null) {
            log.warn("report rejected: deviceId={} not registered", deviceId);
            throw new DeviceNotFoundException(deviceId);
        }
        Instant now = clock.instant();
        if (timestamp == null) {
            log.warn("report REJECTED_MISSING_TIMESTAMP: deviceId={} now={}", deviceId, now);
            return new ReportResult(ReportVerdict.REJECTED_MISSING_TIMESTAMP,
                    "report timestamp is missing", device.getStatus(), now);
        }

        Instant previous = device.getLastReportTimestamp();
        if (timestamp.isAfter(now.plus(FUTURE_TOLERANCE))) {
            log.warn("report REJECTED_FUTURE_TIMESTAMP: deviceId={} timestamp={} now={} tolerance={}s",
                    deviceId, timestamp, now, FUTURE_TOLERANCE.toSeconds());
            return new ReportResult(ReportVerdict.REJECTED_FUTURE_TIMESTAMP,
                    "report timestamp " + timestamp + " is more than "
                            + FUTURE_TOLERANCE.toSeconds() + "s in the future",
                    device.getStatus(), now);
        }
        if (previous != null && timestamp.equals(previous)) {
            log.info("report IGNORED_DUPLICATE: deviceId={} timestamp={} (equals previous accepted)",
                    deviceId, timestamp);
            return new ReportResult(ReportVerdict.IGNORED_DUPLICATE,
                    "duplicate report with timestamp " + timestamp + ", ignored",
                    device.getStatus(), now);
        }
        if (previous != null && timestamp.isBefore(previous)) {
            log.info("report IGNORED_STALE_TIMESTAMP: deviceId={} timestamp={} previousAccepted={} (clock moved backwards/out-of-order)",
                    deviceId, timestamp, previous);
            return new ReportResult(ReportVerdict.IGNORED_STALE_TIMESTAMP,
                    "stale/out-of-order timestamp " + timestamp + " < previous accepted " + previous,
                    device.getStatus(), now);
        }

        // 合法上报：仅在这里刷新存活轨迹与在线状态。
        device.setLastReportTimestamp(timestamp);
        device.setLastSeenAt(now);
        device.setStatus(DeviceStatus.ONLINE);
        log.info("report ACCEPTED: deviceId={} timestamp={} previousAccepted={} observedAt={} metricsKeys={}",
                deviceId, timestamp, previous, now,
                metrics == null ? 0 : metrics.size());
        return new ReportResult(ReportVerdict.ACCEPTED,
                "report accepted, liveness refreshed", DeviceStatus.ONLINE, now);
    }

    public Duration futureTolerance() {
        return FUTURE_TOLERANCE;
    }
}
