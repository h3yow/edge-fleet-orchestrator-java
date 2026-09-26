package com.github.h3yow.edgefleetorchestratorjava.tests;

import com.github.h3yow.edgefleetorchestratorjava.domain.CapabilityRange;
import com.github.h3yow.edgefleetorchestratorjava.domain.Version;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 版本解析/比较与兼容范围判定测试：打印关键输入与判定依据。
 */
class VersionAndRangeTest {

    private static final Logger log = LoggerFactory.getLogger(VersionAndRangeTest.class);

    @Test
    void parsesAndOrdersVersions() {
        Version v1 = Version.parse("1.2.3");
        Version v2 = Version.parse("1.10");
        log.info("input: parse('1.2.3')={}, parse('1.10')={} | basis: numeric segment comparison", v1, v2);
        assertEquals(new Version(1, 2, 3), v1);
        assertEquals(new Version(1, 10, 0), v2);
        assertTrue(v1.compareTo(v2) < 0, "1.2.3 must be lower than 1.10.0 numerically");
        assertThrows(IllegalArgumentException.class, () -> Version.parse("1.x"));
    }

    @Test
    void rangeExplainsEachRejectionReason() {
        CapabilityRange range = new CapabilityRange(Version.parse("2.0"), Version.parse("3.0"), "canbus");

        String tooOld = range.describeGap(Version.parse("1.9.9"), Set.of("canbus"));
        log.info("input: firmware=1.9.9 capabilities=[canbus] -> gap='{}'", tooOld);
        assertTrue(tooOld.contains("below accepted minimum"));

        String tooNew = range.describeGap(Version.parse("3.0.1"), Set.of("canbus"));
        log.info("input: firmware=3.0.1 capabilities=[canbus] -> gap='{}'", tooNew);
        assertTrue(tooNew.contains("above accepted maximum"));

        String missing = range.describeGap(Version.parse("2.5"), Set.of("modbus"));
        log.info("input: firmware=2.5 capabilities=[modbus] -> gap='{}'", missing);
        assertTrue(missing.contains("missing required capability"));

        String ok = range.describeGap(Version.parse("2.5"), Set.of("canbus", "modbus"));
        log.info("input: firmware=2.5 capabilities=[canbus,modbus] -> gap='{}' (empty means accepted)", ok);
        assertEquals("", ok);
        assertTrue(range.covers(Version.parse("3.0"), Set.of("canbus")), "boundary 3.0 inclusive");
    }
}
