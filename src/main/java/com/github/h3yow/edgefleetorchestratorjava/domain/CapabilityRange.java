package com.github.h3yow.edgefleetorchestratorjava.domain;

import java.util.Set;

/**
 * 可接受的设备能力/版本范围：
 * <ul>
 *   <li>固件版本需落在 [minFirmware, maxFirmware] 闭区间（边界可相等）；</li>
 *   <li>当 requiredCapability 非空时，设备能力集合必须包含该项。</li>
 * </ul>
 * {@link #describeGap} 给出人类可解释的差距说明，用于明确拒绝而非静默按旧语义处理。
 */
public record CapabilityRange(Version minFirmware, Version maxFirmware, String requiredCapability) {

    public CapabilityRange {
        if (minFirmware == null || maxFirmware == null) {
            throw new IllegalArgumentException("minFirmware and maxFirmware must not be null");
        }
        if (minFirmware.compareTo(maxFirmware) > 0) {
            throw new IllegalArgumentException(
                    "minFirmware " + minFirmware + " must not be greater than maxFirmware " + maxFirmware);
        }
        requiredCapability = requiredCapability == null ? "" : requiredCapability.trim();
    }

    public boolean covers(Version firmware, Set<String> capabilities) {
        return describeGap(firmware, capabilities).isEmpty();
    }

    public String describeGap(Version firmware, Set<String> capabilities) {
        if (firmware == null) {
            return "device firmware version is unknown";
        }
        if (firmware.compareTo(minFirmware) < 0) {
            return "firmware " + firmware + " below accepted minimum " + minFirmware;
        }
        if (firmware.compareTo(maxFirmware) > 0) {
            return "firmware " + firmware + " above accepted maximum " + maxFirmware;
        }
        if (!requiredCapability.isEmpty() && (capabilities == null || !capabilities.contains(requiredCapability))) {
            return "missing required capability '" + requiredCapability + "'";
        }
        return "";
    }
}
