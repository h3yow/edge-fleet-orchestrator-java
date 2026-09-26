package com.github.h3yow.edgefleetorchestratorjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.h3yow.edgefleetorchestratorjava.model.ReportResult;
import com.github.h3yow.edgefleetorchestratorjava.model.ReportVerdict;
import com.github.h3yow.edgefleetorchestratorjava.registry.DeviceRegistry;
import com.github.h3yow.edgefleetorchestratorjava.report.ReportIngestService;
import com.github.h3yow.edgefleetorchestratorjava.session.GatewaySimulator;
import com.github.h3yow.edgefleetorchestratorjava.session.ReportChannel;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 上报通道（设备侧客户端）与直接入口给出一致结论。 */
class ReportChannelTest {

    private static final Logger log = LoggerFactory.getLogger(ReportChannelTest.class);

    @Test
    void channelDeliversSameVerdictsAsDirectIngest() {
        MutableClock clock = MutableClock.startAt("2026-09-26T00:00:00Z");
        DeviceRegistry registry = new DeviceRegistry(clock, new GatewaySimulator());
        ReportIngestService ingest = new ReportIngestService(registry, clock);
        registry.register("c1", "owner-a", "1.0.0", null);

        ReportResult accepted = ReportChannel.heartbeat("c1", clock.instant()).send(ingest);
        log.info("[channel] deviceId=c1 timestamp={} => {}", clock.instant(), accepted.verdict());
        assertEquals(ReportVerdict.ACCEPTED, accepted.verdict());

        ReportResult dup = ReportChannel.heartbeat("c1", clock.instant()).send(ingest);
        log.info("[channel] duplicate timestamp={} => {}", clock.instant(), dup.verdict());
        assertEquals(ReportVerdict.IGNORED_DUPLICATE, dup.verdict());
    }
}
