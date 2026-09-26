package com.github.h3yow.edgefleetorchestratorjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.h3yow.edgefleetorchestratorjava.model.Device;
import com.github.h3yow.edgefleetorchestratorjava.model.DeviceStatus;
import com.github.h3yow.edgefleetorchestratorjava.model.ReportResult;
import com.github.h3yow.edgefleetorchestratorjava.model.ReportVerdict;
import com.github.h3yow.edgefleetorchestratorjava.registry.DeviceRegistry;
import com.github.h3yow.edgefleetorchestratorjava.report.LivenessService;
import com.github.h3yow.edgefleetorchestratorjava.report.ReportIngestService;
import com.github.h3yow.edgefleetorchestratorjava.session.GatewaySimulator;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 乱序 / 重复 / 缺失 / 未来时间戳上报与 TTL 过期判定。
 * 日志打印关键输入（deviceId、时间戳）与判定依据（previous、now、结论）。
 */
class ReportIngestTest {

    private static final Logger log = LoggerFactory.getLogger(ReportIngestTest.class);

    private MutableClock clock = MutableClock.startAt("2026-09-26T00:00:00Z");

    private DeviceRegistry registry() {
        return new DeviceRegistry(clock, new GatewaySimulator());
    }

    @Test
    void acceptsOrderedReportsThenRejectsDuplicateStaleAndMissing() {
        DeviceRegistry registry = registry();
        ReportIngestService ingest = new ReportIngestService(registry, clock);
        Device device = registry.register("dev-1", "owner-a", "1.0.0", null);

        Instant t1 = clock.instant();
        ReportResult r1 = ingest.ingest("dev-1", t1, Map.of("cpu", 10));
        log.info("[case duplicate/stale] input deviceId=dev-1 timestamp={} => verdict={} reason={}",
                t1, r1.verdict(), r1.reason());
        assertEquals(ReportVerdict.ACCEPTED, r1.verdict());
        assertEquals(DeviceStatus.ONLINE, device.getStatus());

        clock.advanceSeconds(10);
        Instant t2 = clock.instant();

        // 完全相同时间戳：重复，忽略
        ReportResult dup = ingest.ingest("dev-1", t1, Map.of());
        log.info("[case duplicate] input deviceId=dev-1 timestamp={} (previousAccepted={}) => verdict={} reason={}",
                t1, t1, dup.verdict(), dup.reason());
        assertEquals(ReportVerdict.IGNORED_DUPLICATE, dup.verdict());

        // 更早的时间戳：乱序/回退，忽略且不得覆盖 lastReportTimestamp
        Instant back = t1.minusSeconds(30);
        ReportResult stale = ingest.ingest("dev-1", back, Map.of());
        log.info("[case stale] input deviceId=dev-1 timestamp={} (previousAccepted={}) => verdict={} reason={}",
                back, t1, stale.verdict(), stale.reason());
        assertEquals(ReportVerdict.IGNORED_STALE_TIMESTAMP, stale.verdict());
        assertEquals(t1, device.getLastReportTimestamp(), "stale report must not overwrite state");

        // 缺失时间戳：拒绝
        ReportResult missing = ingest.ingest("dev-1", null, Map.of());
        log.info("[case missing] input deviceId=dev-1 timestamp=null => verdict={} reason={}",
                missing.verdict(), missing.reason());
        assertEquals(ReportVerdict.REJECTED_MISSING_TIMESTAMP, missing.verdict());

        // 超前超过容忍窗口：拒绝
        Instant future = clock.instant().plusSeconds(3600);
        ReportResult fut = ingest.ingest("dev-1", future, Map.of());
        log.info("[case future] input deviceId=dev-1 timestamp={} now={} => verdict={} reason={}",
                future, clock.instant(), fut.verdict(), fut.reason());
        assertEquals(ReportVerdict.REJECTED_FUTURE_TIMESTAMP, fut.verdict());

        // 被忽略/拒绝后仍可接受正常顺序上报
        ReportResult r2 = ingest.ingest("dev-1", t2, Map.of());
        log.info("[case recover] input deviceId=dev-1 timestamp={} => verdict={}", t2, r2.verdict());
        assertEquals(ReportVerdict.ACCEPTED, r2.verdict());
        assertEquals(t2, device.getLastReportTimestamp());
        assertNotNull(device.getLastSeenAt());
    }

    @Test
    void deviceGoesOfflineWhenHeartbeatTtlExpiresAndRecoversOnNextAcceptedReport() {
        DeviceRegistry registry = registry();
        ReportIngestService ingest = new ReportIngestService(registry, clock);
        LivenessService liveness = new LivenessService(registry, clock, Duration.ofSeconds(30));
        registry.register("dev-2", "owner-a", "1.0.0", null);

        ingest.ingest("dev-2", clock.instant(), Map.of());
        assertEquals(DeviceStatus.ONLINE, liveness.observe(registry.find("dev-2")).getStatus());

        clock.advanceSeconds(31);
        int expired = liveness.sweep();
        Device after = liveness.observe(registry.find("dev-2"));
        log.info("[case ttl] input deviceId=dev-2 advanced=31s ttl=30s => newlyExpired={} status={}",
                expired, after.getStatus());
        assertEquals(1, expired);
        assertEquals(DeviceStatus.OFFLINE, after.getStatus());

        // 过期期间一个合法顺序上报可使其恢复 ONLINE；乱序上报则不能
        clock.advanceSeconds(1);
        Instant staleTs = Instant.parse("2026-09-25T23:59:50Z");
        ReportResult stale = ingest.ingest("dev-2", staleTs, Map.of());
        log.info("[case ttl-recover-stale] timestamp={} => verdict={} status={}",
                staleTs, stale.verdict(), stale.deviceStatus());
        assertEquals(ReportVerdict.IGNORED_STALE_TIMESTAMP, stale.verdict());
        assertEquals(DeviceStatus.OFFLINE, registry.find("dev-2").getStatus());

        ReportResult fresh = ingest.ingest("dev-2", clock.instant(), Map.of());
        log.info("[case ttl-recover] timestamp={} => verdict={} status={}",
                clock.instant(), fresh.verdict(), fresh.deviceStatus());
        assertEquals(ReportVerdict.ACCEPTED, fresh.verdict());
        assertEquals(DeviceStatus.ONLINE, registry.find("dev-2").getStatus());
    }
}
