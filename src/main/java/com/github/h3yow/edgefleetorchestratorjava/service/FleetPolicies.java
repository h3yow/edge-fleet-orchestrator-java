package com.github.h3yow.edgefleetorchestratorjava.service;

import java.time.Duration;

/**
 * 全局策略参数（可通过 application.properties 覆盖）。
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "edge.fleet")
public record FleetPolicies(Duration heartbeatTtl,
                            Duration maxReportBacklog,
                            Duration maxClockSkew) {

    public FleetPolicies {
        if (heartbeatTtl == null) heartbeatTtl = Duration.ofSeconds(30);
        if (maxReportBacklog == null) maxReportBacklog = Duration.ofSeconds(60);
        if (maxClockSkew == null) maxClockSkew = Duration.ofSeconds(5);
    }
}
