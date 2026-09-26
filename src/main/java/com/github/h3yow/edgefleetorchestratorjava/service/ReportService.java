package com.github.h3yow.edgefleetorchestratorjava.service;

import com.github.h3yow.edgefleetorchestratorjava.domain.ApiException;
import com.github.h3yow.edgefleetorchestratorjava.domain.Device;
import com.github.h3yow.edgefleetorchestratorjava.domain.LivenessState;
import com.github.h3yow.edgefleetorchestratorjava.domain.ReportDisposition;
import com.github.h3yow.edgefleetorchestratorjava.domain.ReportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * 周期上报处理。结论语义：
 * 判定顺序（原因可区分）：完全重复 → 未来偏移 → 过旧 → 相对最近已接受上报的回退/乱序。
 * <ul>
 *   <li>ACCEPTED：刷新存活时间（记录的是设备自报时间）与单调序列号；</li>
 *   <li>REJECTED_UNREGISTERED：设备未注册；</li>
 *   <li>IGNORED_DUPLICATE：与最新已接受上报序列号相同（重复重放，无副作用）；</li>
 *   <li>REJECTED_FUTURE_SKEW：晚于服务端当前时间加上时钟偏移上限；</li>
 *   <li>REJECTED_TIMESTAMP_TOO_OLD：早于服务端当前时间减去可接受积压窗口；</li>
 *   <li>REJECTED_TIMESTAMP_REGRESSED：通过新鲜度窗口但时间戳/序列号早于已接受的最新上报（乱序/回退）。</li>
 * </ul>
 * 任何拒绝/忽略都不会刷新 TTL，也不会覆盖已有状态。判定与设备级下发共用 Device 实例锁。
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final DeviceRegistry registry;
    private final Clock clock;
    private final FleetPolicies policies;

    public ReportService(DeviceRegistry registry, Clock clock, FleetPolicies policies) {
        this.registry = registry;
        this.clock = clock;
        this.policies = policies;
    }

    public ReportResult ingest(String deviceId, long seq, Instant reportTime) {
        if (reportTime == null) {
            throw new ApiException("INVALID_REQUEST", "reportTime must not be null");
        }
        Instant now = Instant.now(clock);
        Device device = registry.find(deviceId);
        if (device == null) {
            log.warn("report REJECTED_UNREGISTERED | deviceId={} | seq={} | reportTime={} | basis=device not registered",
                    deviceId, seq, reportTime);
            return new ReportResult(deviceId, seq, reportTime,
                    ReportDisposition.REJECTED_UNREGISTERED, "device is not registered", LivenessState.OFFLINE);
        }

        synchronized (device) {
            Instant lastTime = device.lastAcceptedReportAt();
            long lastSeq = device.lastAcceptedSeq();

            // 判定顺序（各原因可区分）：
            // 1) 完全重复（同 seq）：直接忽略，避免重复重放被后续窗口检查误判；
            // 2) 未来偏移：包自身时间戳超过服务端当前时间 + skew；
            // 3) 过旧：包自身时间戳早于服务端当前时间 - backlog 窗口；
            // 4) 回退/乱序：包通过了新鲜度窗口，但早于或等于最近已接受的上报。
            if (lastTime != null && seq == lastSeq) {
                return reject(device, seq, reportTime, now,
                        ReportDisposition.IGNORED_DUPLICATE,
                        "duplicate seq " + seq + " (lastAcceptedSeq=" + lastSeq + "); report has no effect");
            }

            if (reportTime.isAfter(now.plus(policies.maxClockSkew()))) {
                return reject(device, seq, reportTime, now,
                        ReportDisposition.REJECTED_FUTURE_SKEW,
                        "reportTime " + reportTime + " is more than " + policies.maxClockSkew().toSeconds()
                                + "s ahead of server time " + now);
            }
            if (reportTime.isBefore(now.minus(policies.maxReportBacklog()))) {
                return reject(device, seq, reportTime, now,
                        ReportDisposition.REJECTED_TIMESTAMP_TOO_OLD,
                        "reportTime " + reportTime + " is older than " + policies.maxReportBacklog().toSeconds()
                                + "s backlog window against server time " + now);
            }

            if (lastTime != null
                    && (reportTime.isBefore(lastTime) || reportTime.equals(lastTime) || seq < lastSeq)) {
                return reject(device, seq, reportTime, now,
                        ReportDisposition.REJECTED_TIMESTAMP_REGRESSED,
                        "regressed report (seq=" + seq + " vs lastAcceptedSeq=" + lastSeq
                                + ", reportTime=" + reportTime + " vs lastAcceptedTime=" + lastTime + ")");
            }

            device.setLastAcceptedReportAt(reportTime);
            device.setLastAcceptedSeq(seq);
            LivenessState liveness = device.livenessAt(now);
            log.info("report ACCEPTED | deviceId={} | seq={} | reportTime={} | serverNow={} | lastAcceptedSeq={} | liveness={} | ttl={}s",
                    deviceId, seq, reportTime, now, seq, liveness, device.ttl().toSeconds());
            return new ReportResult(deviceId, seq, reportTime, ReportDisposition.ACCEPTED,
                    "heartbeat refreshed at " + reportTime, liveness);
        }
    }

    private ReportResult reject(Device device, long seq, Instant reportTime, Instant now,
                                ReportDisposition disposition, String reason) {
        log.warn("report {} | deviceId={} | seq={} | reportTime={} | serverNow={} | lastAcceptedSeq={} | lastAcceptedTime={} | liveness={} | basis={}",
                disposition, device.id(), seq, reportTime, now,
                device.lastAcceptedSeq(), device.lastAcceptedReportAt(), device.livenessAt(now), reason);
        return new ReportResult(device.id(), seq, reportTime, disposition, reason, device.livenessAt(now));
    }
}
