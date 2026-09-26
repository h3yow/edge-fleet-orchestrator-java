package com.github.h3yow.edgefleetorchestratorjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.h3yow.edgefleetorchestratorjava.DeviceNotFoundException;
import com.github.h3yow.edgefleetorchestratorjava.OwnerMismatchException;
import com.github.h3yow.edgefleetorchestratorjava.compat.CompatibilityPolicy;
import com.github.h3yow.edgefleetorchestratorjava.model.DispatchRequest;
import com.github.h3yow.edgefleetorchestratorjava.model.StateRequirements;
import com.github.h3yow.edgefleetorchestratorjava.orchestration.OrchestrationService;
import com.github.h3yow.edgefleetorchestratorjava.registry.DeviceRegistry;
import com.github.h3yow.edgefleetorchestratorjava.session.GatewaySimulator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 归属方隔离：跨归属方拒绝，且与"目标不存在"明确可区分。 */
class AccessControlTest {

    private static final Logger log = LoggerFactory.getLogger(AccessControlTest.class);

    private final MutableClock clock = MutableClock.startAt("2026-09-26T00:00:00Z");
    private final GatewaySimulator gateway = new GatewaySimulator();
    private final DeviceRegistry registry = new DeviceRegistry(clock, gateway);
    private final OrchestrationService svc =
            new OrchestrationService(registry, new CompatibilityPolicy(), gateway, clock);

    private DispatchRequest dispatchAs(String deviceId, String owner) {
        return new DispatchRequest(deviceId, owner, Map.of("k", "v"), StateRequirements.none());
    }

    @Test
    void crossOwnerAccessIsForbiddenAndDistinctFromMissingTarget() {
        registry.register("owned-a", "owner-a", "1.0.0", null);

        // 跨归属方下发/重试/撤销 -> OwnerMismatchException（HTTP 403 语义）
        OwnerMismatchException e1 = assertThrows(OwnerMismatchException.class,
                () -> svc.dispatch(dispatchAs("owned-a", "owner-b")));
        log.warn("[access] cross-owner dispatch => {} (expected 403)", e1.getMessage());
        OwnerMismatchException e2 = assertThrows(OwnerMismatchException.class,
                () -> svc.retry("owned-a", "owner-b"));
        OwnerMismatchException e3 = assertThrows(OwnerMismatchException.class,
                () -> svc.revoke("owned-a", "owner-b"));
        assertEquals(OwnerMismatchException.class, e1.getClass());
        assertEquals(OwnerMismatchException.class, e2.getClass());
        assertEquals(OwnerMismatchException.class, e3.getClass());

        // 目标不存在 -> DeviceNotFoundException（HTTP 404 语义），与越权区分
        DeviceNotFoundException nf = assertThrows(DeviceNotFoundException.class,
                () -> svc.dispatch(dispatchAs("ghost", "owner-a")));
        log.warn("[access] missing device dispatch => {} (expected 404)", nf.getMessage());
        assertThrows(DeviceNotFoundException.class, () -> svc.retry("ghost", "owner-a"));
        assertThrows(DeviceNotFoundException.class, () -> svc.revoke("ghost", "owner-a"));
    }
}
