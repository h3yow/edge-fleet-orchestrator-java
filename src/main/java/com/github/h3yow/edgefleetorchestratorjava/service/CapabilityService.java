package com.github.h3yow.edgefleetorchestratorjava.service;

import com.github.h3yow.edgefleetorchestratorjava.domain.CapabilityRange;
import com.github.h3yow.edgefleetorchestratorjava.domain.CompatibilityVerdict;
import com.github.h3yow.edgefleetorchestratorjava.domain.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 设备能力与版本兼容判定：不在可接受范围内必须明确拒绝并说明差距，
 * 而不是按旧语义静默放行。
 */
@org.springframework.stereotype.Service
public class CapabilityService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityService.class);

    public CompatibilityVerdict evaluate(Version firmware, Set<String> capabilities,
                                         CapabilityRange acceptedRange) {
        if (acceptedRange == null) {
            log.info("compatibility check skipped: no accepted range supplied, firmware={}", firmware);
            return new CompatibilityVerdict(true, "");
        }
        String gap = acceptedRange.describeGap(firmware, capabilities);
        if (gap.isEmpty()) {
            log.info("compatibility ACCEPTED | firmware={} | range=[{},{}] | requiredCapability='{}' | capabilities={}",
                    firmware, acceptedRange.minFirmware(), acceptedRange.maxFirmware(),
                    acceptedRange.requiredCapability(), capabilities);
            return new CompatibilityVerdict(true, "");
        }
        log.warn("compatibility REJECTED | firmware={} | range=[{},{}] | requiredCapability='{}' | capabilities={} | reason={}",
                firmware, acceptedRange.minFirmware(), acceptedRange.maxFirmware(),
                acceptedRange.requiredCapability(), capabilities, gap);
        return new CompatibilityVerdict(false, gap);
    }
}
