package com.github.h3yow.edgefleetorchestratorjava.tests;

import com.github.h3yow.edgefleetorchestratorjava.domain.*;
import com.github.h3yow.edgefleetorchestratorjava.service.CapabilityService;
import com.github.h3yow.edgefleetorchestratorjava.service.DeviceRegistry;
import com.github.h3yow.edgefleetorchestratorjava.service.FleetPolicies;
import com.github.h3yow.edgefleetorchestratorjava.service.ReportService;
import com.github.h3yow.edgefleetorchestratorjava.sim.DeviceSimulator;
import com.github.h3yow.edgefleetorchestratorjava.testsupport.MutableClock;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 存活上报：乱序、重复、回退、过期、未来偏移、TTL 超时均不得让设备无限期保持可用，
 * 且各拒绝/忽略原因可区分。关键输入与判定依据见测试日志。
 */
class ReportLivenessTest {

    private static final Logger log = LoggerFactory.getLogger(ReportLivenessTest.class);

    private record Harness(MutableClock clock, DeviceRegistry registry, ReportService reports) {
    }

    private Harness harness() {
        MutableClock clock = MutableClock.startAt("2026-09-26T00:00:00Z");
        FleetPolicies policies = new FleetPolicies(Duration.ofSeconds(30),
                Duration.ofSeconds(60), Duration.ofSeconds(5));
        DeviceSimulator simulator = new DeviceSimulator(clock);
        CapabilityService caps = new CapabilityService();
        DeviceRegistry registry = new DeviceRegistry(clock, policies, caps, simulator);
        ReportService reports = new ReportService(registry, clock, policies);
        registry.register("gw-1", "acme", Version.parse("2.1.0"), Set.of("canbus"), null);
        return new Harness(clock, registry, reports);
    }

    private ReportResult ingest(Harness h, long seq, String time) {
        ReportResult r = h.reports().ingest("gw-1", seq, Instant.parse(time));
        log.info("[case] deviceId=gw-1 seq={} reportTime={} => disposition={} reason='{}' liveness={}",
                seq, time, r.disposition(), r.reason(), r.livenessAfter());
        return r;
    }

    @Test
    void acceptsHeartbeatThenGoesOfflineAfterTtl() {
        Harness h = harness();
        h.clock().set(Instant.parse("2026-09-26T00:00:00Z"));
        ReportResult first = ingest(h, 1, "2026-09-26T00:00:00Z");
        assertEquals(ReportDisposition.ACCEPTED, first.disposition());
        assertEquals(LivenessState.ONLINE, first.livenessAfter());

        h.clock().advance(Duration.ofSeconds(29));
        assertEquals(LivenessState.ONLINE, h.registry().livenessOf("gw-1"), "29s < ttl 30s");

        h.clock().advance(Duration.ofSeconds(2));
        log.info("[case] advanced to 31s (>ttl=30s); lastAcceptedReport not refreshed -> expect OFFLINE");
        assertEquals(LivenessState.OFFLINE, h.registry().livenessOf("gw-1"));
    }

    @Test
    void rejectsOrIgnoresStaleDuplicateAndRegressedReportsWithoutRefreshingTtl() {
        Harness h = harness();
        // 服务端时钟推进到与设备自报时刻一致，首条心跳被接受
        h.clock().set(Instant.parse("2026-09-26T00:00:10Z"));
        ingest(h, 10, "2026-09-26T00:00:10Z");

        // 完全重复：忽略
        ReportResult duplicate = ingest(h, 10, "2026-09-26T00:00:10Z");
        assertEquals(ReportDisposition.IGNORED_DUPLICATE, duplicate.disposition());

        // 时间戳回退（序列号也回退）：拒绝
        ReportResult regressed = ingest(h, 9, "2026-09-26T00:00:09Z");
        assertEquals(ReportDisposition.REJECTED_TIMESTAMP_REGRESSED, regressed.disposition());

        // 时间戳相同但序列号更新：仍按回退/乱序拒绝
        ReportResult sameTime = ingest(h, 11, "2026-09-26T00:00:10Z");
        assertEquals(ReportDisposition.REJECTED_TIMESTAMP_REGRESSED, sameTime.disposition());

        // 超过积压窗口的老上报：拒绝（原因可区分）
        ReportResult tooOld = ingest(h, 12, "2026-09-25T23:58:00Z");
        assertEquals(ReportDisposition.REJECTED_TIMESTAMP_TOO_OLD, tooOld.disposition());

        // 超过时钟偏移上限的未来上报：拒绝
        ReportResult future = ingest(h, 13, "2026-09-26T00:00:30Z");
        assertEquals(ReportDisposition.REJECTED_FUTURE_SKEW, future.disposition());

        // 关键不变量：所有拒绝/忽略均未续期，最近接受时间仍停留在 seq=10
        h.clock().advance(Duration.ofSeconds(31)); // now=00:00:41，距 00:00:10 已 31s > ttl
        assertEquals(LivenessState.OFFLINE, h.registry().livenessOf("gw-1"),
                "rejected/ignored reports must not keep the device alive");
        assertEquals(Instant.parse("2026-09-26T00:00:10Z"),
                h.registry().requireExisting("gw-1").lastAcceptedReportAt());
    }

    @Test
    void outOfOrderReportsProduceSameConclusionRegardlessOfArrival() {
        // 乱序到达两次：先 seq=5 后 seq=4，再重放 seq=5 —— 最终接受点唯一且可解释
        Harness h = harness();
        h.clock().set(Instant.parse("2026-09-26T00:00:05Z"));
        ReportResult later = ingest(h, 5, "2026-09-26T00:00:05Z");
        ReportResult earlier = ingest(h, 4, "2026-09-26T00:00:04Z");
        ReportResult replay = ingest(h, 5, "2026-09-26T00:00:05Z");
        assertAll(
                () -> assertEquals(ReportDisposition.ACCEPTED, later.disposition()),
                () -> assertEquals(ReportDisposition.REJECTED_TIMESTAMP_REGRESSED, earlier.disposition()),
                () -> assertEquals(ReportDisposition.IGNORED_DUPLICATE, replay.disposition())
        );
        assertEquals(5L, h.registry().requireExisting("gw-1").lastAcceptedSeq(),
                "out-of-order replay must not overwrite the newer accepted state");
    }

    @Test
    void unregisteredDeviceReportsAreRejected() {
        MutableClock clock = MutableClock.startAt("2026-09-26T00:00:00Z");
        FleetPolicies policies = new FleetPolicies(Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(5));
        DeviceRegistry registry = new DeviceRegistry(clock, policies, new CapabilityService(),
                new DeviceSimulator(clock));
        ReportService reports = new ReportService(registry, clock, policies);
        ReportResult r = reports.ingest("ghost", 1, Instant.now(clock));
        log.info("[case] deviceId=ghost seq=1 => disposition={} reason='{}'", r.disposition(), r.reason());
        assertEquals(ReportDisposition.REJECTED_UNREGISTERED, r.disposition());
        assertEquals(LivenessState.OFFLINE, r.livenessAfter());
    }
}
