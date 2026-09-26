package com.github.h3yow.edgefleetorchestratorjava.compat;

import com.github.h3yow.edgefleetorchestratorjava.model.Device;
import com.github.h3yow.edgefleetorchestratorjava.model.StateRequirements;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 能力与固件版本兼容判定。
 *
 * <p>不在可接受范围内的设备必须被明确拒绝（返回违规原因列表），
 * 调用方依据非空列表拒绝下发，而非按旧语义静默放行。
 * 固件区间语义为闭区间：{@code minFirmware <= device <= maxFirmware}。
 */
@Component
public class CompatibilityPolicy {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityPolicy.class);

    public List<String> violations(Device device, StateRequirements requirements) {
        List<String> violations = new ArrayList<>();
        if (requirements == null) {
            return violations;
        }
        if (requirements.requiredCapabilities() != null) {
            for (String required : requirements.requiredCapabilities()) {
                if (device.getCapabilities() == null
                        || !device.getCapabilities().contains(required)) {
                    violations.add("missing required capability: " + required);
                }
            }
        }
        String firmware = device.getFirmwareVersion();
        if (requirements.minFirmware() != null
                && compareVersions(firmware, requirements.minFirmware()) < 0) {
            violations.add("firmware " + firmware + " below minimum " + requirements.minFirmware());
        }
        if (requirements.maxFirmware() != null
                && compareVersions(firmware, requirements.maxFirmware()) > 0) {
            violations.add("firmware " + firmware + " above maximum " + requirements.maxFirmware());
        }
        if (!violations.isEmpty()) {
            log.warn("compatibility check failed: deviceId={} firmware={} requirements={} violations={}",
                    device.getDeviceId(), firmware, requirements, violations);
        }
        return violations;
    }

    /**
     * 简易点分版本比较：按 '.' 切分为数字段比较；缺失段视为 0，非数字段回退为字符串比较。
     *
     * @return 负数/0/正数，分别表示 left 早于/等于/晚于 right
     */
    static int compareVersions(String left, String right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        String[] ls = left.split("\\.");
        String[] rs = right.split("\\.");
        int n = Math.max(ls.length, rs.length);
        for (int i = 0; i < n; i++) {
            int cmp = compareSegment(
                    i < ls.length ? ls[i].trim() : "0",
                    i < rs.length ? rs[i].trim() : "0");
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int compareSegment(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }
}
