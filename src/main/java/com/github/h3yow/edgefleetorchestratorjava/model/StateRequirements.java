package com.github.h3yow.edgefleetorchestratorjava.model;

import java.util.Set;

/** 期望状态对设备的能力与固件版本要求（兼容范围）。 */
public record StateRequirements(
        Set<String> requiredCapabilities,
        String minFirmware,
        String maxFirmware
) {
    public static StateRequirements none() {
        return new StateRequirements(Set.of(), null, null);
    }
}
