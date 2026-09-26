package com.github.h3yow.edgefleetorchestratorjava.tests;

import com.github.h3yow.edgefleetorchestratorjava.domain.*;
import com.github.h3yow.edgefleetorchestratorjava.service.CapabilityService;
import com.github.h3yow.edgefleetorchestratorjava.service.DeviceRegistry;
import com.github.h3yow.edgefleetorchestratorjava.service.FleetPolicies;
import com.github.h3yow.edgefleetorchestratorjava.service.FleetService;
import com.github.h3yow.edgefleetorchestratorjava.sim.DeviceSimulator;
import com.github.h3yow.edgefleetorchestratorjava.testsupport.MutableClock;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 归属方隔离与兼容范围测试：越权、目标不存在、不兼容三类结论必须可区分。
 */
class AuthzAndCompatibilityTest {

    private static final Logger log = LoggerFactory.getLogger(AuthzAndCompatibilityTest.class);
    private static final CapabilityRange RANGE = new CapabilityRange(Version.parse("2.0"),
            Version.parse("3.0"), "canbus");

    private record Harness(DeviceRegistry registry, FleetService fleet) {
    }

    private Harness harness() {
        MutableClock clock = MutableClock.startAt("2026-09-26T00:00:00Z");
        FleetPolicies policies = new FleetPolicies(Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(5));
        DeviceSimulator simulator = new DeviceSimulator(clock);
        CapabilityService caps = new CapabilityService();
        DeviceRegistry registry = new DeviceRegistry(clock, policies, caps, simulator);
        FleetService fleet = new FleetService(registry, caps, simulator, clock);
        registry.register("gw-1", "acme", Version.parse("2.1.0"), Set.of("canbus"), null);
        return new Harness(registry, fleet);
    }

    private static ApiException expectApi(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    @Test
    void distinguishesNotFoundFromCrossOwnerForbidden() {
        Harness h = harness();

        ApiException notFound = expectApi(() -> h.registry().requireOwned("gw-x", "acme"));
        ApiException forbidden = expectApi(() -> h.registry().requireOwned("gw-1", "globex"));
        log.info("[case] missing target code='{}' msg='{}'", notFound.code(), notFound.getMessage());
        log.info("[case] cross-owner  code='{}' msg='{}'", forbidden.code(), forbidden.getMessage());

        assertAll(
                () -> assertEquals("NOT_FOUND", notFound.code()),
                () -> assertEquals("FORBIDDEN_OWNER", forbidden.code())
        );

        // 跨归属方不得读取修订历史，也不得下发或撤销
        ApiException listDenied = expectApi(() -> h.fleet().revisions("gw-1", "globex"));
        ApiException dispatchDenied = expectApi(() ->
                h.fleet().dispatch("gw-1", "globex", Map.of("k", "v"), RANGE));
        ApiException revokeDenied = expectApi(() ->
                h.fleet().revoke("gw-1", "globex", 1L));
        assertEquals("FORBIDDEN_OWNER", listDenied.code());
        assertEquals("FORBIDDEN_OWNER", dispatchDenied.code());
        assertEquals("FORBIDDEN_OWNER", revokeDenied.code());

        // 越权尝试不得产生任何修订
        assertTrue(h.fleet().revisions("gw-1", "acme").isEmpty(),
                "forbidden dispatch must not create a revision");

        // 不存在设备的下发是 NOT_FOUND，而不是 FORBIDDEN
        ApiException dispatchMissing = expectApi(() ->
                h.fleet().dispatch("ghost", "acme", Map.of("k", "v"), RANGE));
        assertEquals("NOT_FOUND", dispatchMissing.code());
    }

    @Test
    void registrationRejectsOutOfRangeFirmwareAndMissingCapability() {
        Harness h = harness();

        ApiException oldFirmware = expectApi(() -> h.registry().register(
                "gw-old", "acme", Version.parse("1.9.9"), Set.of("canbus"), RANGE));
        ApiException noCapability = expectApi(() -> h.registry().register(
                "gw-nocap", "acme", Version.parse("2.5.0"), Set.of("modbus"), RANGE));
        log.info("[case] old firmware rejection code='{}' msg='{}'", oldFirmware.code(), oldFirmware.getMessage());
        log.info("[case] missing capability rejection code='{}' msg='{}'", noCapability.code(), noCapability.getMessage());

        assertAll(
                () -> assertEquals("INCOMPATIBLE", oldFirmware.code()),
                () -> assertTrue(oldFirmware.getMessage().contains("below accepted minimum")),
                () -> assertEquals("INCOMPATIBLE", noCapability.code()),
                () -> assertTrue(noCapability.getMessage().contains("missing required capability"))
        );
    }

    @Test
    void dispatchWithStricterRangeIsRejectedInsteadOfSilentlyAccepted() {
        Harness h = harness();
        // 注册时未限制；下发时调用方给出更严格的兼容范围（要求 2.5+ 与 zigbee），设备不满足
        CapabilityRange strict = new CapabilityRange(Version.parse("2.5"), Version.parse("3.0"), "zigbee");
        ApiException ex = expectApi(() -> h.fleet().dispatch("gw-1", "acme", Map.of("k", "v"), strict));
        log.info("[case] dispatch incompatibility code='{}' msg='{}'", ex.code(), ex.getMessage());
        assertEquals("INCOMPATIBLE", ex.code());

        // 被兼容门拒绝的修订留在 REJECTED 终态，后续 reconcile 不会重新应用
        long created = h.registry().requireExisting("gw-1").revisionSeq();
        assertEquals(1L, created, "revision must still be recorded for auditability");
        DispatchResult replay = h.fleet().reconcile("gw-1", "acme", 1L, strict);
        assertEquals(RevisionState.REJECTED, replay.finalState());
        assertEquals(DispatchOutcome.INCOMPATIBLE, replay.outcome());
    }

    @Test
    void duplicateRegistrationAndMissingRevisionAreDistinct() {
        Harness h = harness();
        ApiException dup = expectApi(() -> h.registry().register(
                "gw-1", "acme", Version.parse("2.1.0"), Set.of("canbus"), null));
        ApiException noRevision = expectApi(() ->
                h.fleet().reconcile("gw-1", "acme", 99L, RANGE));
        log.info("[case] duplicate registration code='{}'; missing revision code='{}'",
                dup.code(), noRevision.code());
        assertAll(
                () -> assertEquals("DEVICE_ALREADY_EXISTS", dup.code()),
                () -> assertEquals("REVISION_NOT_FOUND", noRevision.code())
        );
    }
}
