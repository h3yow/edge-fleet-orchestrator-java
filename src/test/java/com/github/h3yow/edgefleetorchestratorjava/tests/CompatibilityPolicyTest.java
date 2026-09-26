package com.github.h3yow.edgefleetorchestratorjava.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.h3yow.edgefleetorchestratorjava.compat.CompatibilityPolicy;
import com.github.h3yow.edgefleetorchestratorjava.model.Device;
import com.github.h3yow.edgefleetorchestratorjava.model.StateRequirements;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 固件版本区间与能力集合判定，拒绝原因必须明确。 */
class CompatibilityPolicyTest {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityPolicyTest.class);

    private final CompatibilityPolicy policy = new CompatibilityPolicy();

    private Device device(String firmware, Set<String> caps) {
        return new Device("d", "owner-a", firmware, caps, Instant.EPOCH);
    }

    @Test
    void evaluatesFirmwareRangeAndCapabilities() {
        StateRequirements req = new StateRequirements(
                Set.of("gps", "ble"), "1.2.0", "2.0.0");

        List<String> ok = policy.violations(device("1.5.0", Set.of("gps", "ble", "wifi")), req);
        log.info("[compat ok] firmware=1.5.0 caps={} => violations={}", Set.of("gps", "ble"), ok);
        assertTrue(ok.isEmpty());

        List<String> oldFw = policy.violations(device("1.1.9", Set.of("gps", "ble")), req);
        log.info("[compat old-fw] firmware=1.1.9 => violations={}", oldFw);
        assertTrue(oldFw.stream().anyMatch(v -> v.contains("below minimum")));

        List<String> newFw = policy.violations(device("2.0.1", Set.of("gps", "ble")), req);
        log.info("[compat new-fw] firmware=2.0.1 => violations={}", newFw);
        assertTrue(newFw.stream().anyMatch(v -> v.contains("above maximum")));

        List<String> missingCaps = policy.violations(device("1.5.0", Set.of("gps")), req);
        log.info("[compat caps] caps={} => violations={}", Set.of("gps"), missingCaps);
        assertTrue(missingCaps.stream().anyMatch(v -> v.contains("missing required capability: ble")));
        assertFalse(missingCaps.isEmpty(), "out-of-range device must be explicitly rejected");

        // 边界版本（闭区间）
        assertTrue(policy.violations(device("1.2.0", Set.of("gps", "ble")), req).isEmpty());
        assertTrue(policy.violations(device("2.0.0", Set.of("gps", "ble")), req).isEmpty());
    }
}
