package com.github.h3yow.edgefleetorchestratorjava.web;

import com.github.h3yow.edgefleetorchestratorjava.domain.ApiException;
import com.github.h3yow.edgefleetorchestratorjava.domain.CapabilityRange;
import com.github.h3yow.edgefleetorchestratorjava.domain.DesiredRevision;
import com.github.h3yow.edgefleetorchestratorjava.domain.Device;
import com.github.h3yow.edgefleetorchestratorjava.domain.DispatchResult;
import com.github.h3yow.edgefleetorchestratorjava.domain.ReportResult;
import com.github.h3yow.edgefleetorchestratorjava.domain.Version;
import com.github.h3yow.edgefleetorchestratorjava.service.CapabilityService;
import com.github.h3yow.edgefleetorchestratorjava.service.DeviceRegistry;
import com.github.h3yow.edgefleetorchestratorjava.service.FleetService;
import com.github.h3yow.edgefleetorchestratorjava.service.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * REST 入口：注册 / 查询 / 上报 / 下发 / 重放收敛 / 撤销 / 修订历史。
 * 所有写操作携带 owner，由服务层做归属方隔离。
 */
@RestController
@RequestMapping("/api/fleet")
public class FleetController {

    private static final Logger log = LoggerFactory.getLogger(FleetController.class);

    private final DeviceRegistry registry;
    private final ReportService reportService;
    private final FleetService fleetService;
    private final CapabilityService capabilityService;

    public FleetController(DeviceRegistry registry, ReportService reportService,
                           FleetService fleetService, CapabilityService capabilityService) {
        this.registry = registry;
        this.reportService = reportService;
        this.fleetService = fleetService;
        this.capabilityService = capabilityService;
    }

    @PostMapping("/devices")
    public ApiDtos.DeviceView register(@RequestBody ApiDtos.RegisterRequest request) {
        log.info("http register | input={}", request);
        Device device = registry.register(request.deviceId(), request.owner(),
                Version.parse(request.firmware()),
                request.capabilities() == null ? java.util.Set.of() : java.util.Set.copyOf(request.capabilities()),
                toRange(request.acceptedRange()));
        return toDeviceView(device);
    }

    @GetMapping("/devices/{id}")
    public ApiDtos.DeviceView get(@PathVariable String id, @RequestParam String owner) {
        return toDeviceView(registry.requireOwned(id, owner));
    }

    @GetMapping("/devices")
    public List<ApiDtos.DeviceView> list(@RequestParam String owner) {
        return registry.listByOwner(owner).stream().map(this::toDeviceView).toList();
    }

    @PostMapping("/devices/{id}/reports")
    public ApiDtos.ReportView report(@PathVariable String id, @RequestBody ApiDtos.ReportRequest request) {
        log.info("http report | deviceId={} | input={}", id, request);
        Instant reportTime;
        try {
            reportTime = Instant.parse(request.reportTime());
        } catch (Exception e) {
            throw new ApiException("INVALID_REQUEST", "reportTime must be ISO-8601 instant: " + request.reportTime());
        }
        ReportResult result = reportService.ingest(id, request.seq(), reportTime);
        return new ApiDtos.ReportView(result.deviceId(), result.seq(), result.reportTime().toString(),
                result.disposition().name(), result.reason(), result.livenessAfter().name());
    }

    @PostMapping("/devices/{id}/desired")
    public ApiDtos.DispatchView dispatch(@PathVariable String id, @RequestBody ApiDtos.DispatchRequest request) {
        log.info("http dispatch | deviceId={} | owner={} | configKeys={}", id, request.owner(),
                request.config() == null ? null : request.config().keySet());
        DispatchResult result = fleetService.dispatch(id, request.owner(),
                request.config() == null ? java.util.Map.of() : request.config(),
                toRange(request.acceptedRange()));
        return toDispatchView(result);
    }

    @PostMapping("/devices/{id}/revisions/{revision}/reconcile")
    public ApiDtos.DispatchView reconcile(@PathVariable String id, @PathVariable long revision,
                                          @RequestBody ApiDtos.ReviseRequest request) {
        log.info("http reconcile | deviceId={} | revision={} | owner={}", id, revision, request.owner());
        return toDispatchView(fleetService.reconcile(id, request.owner(), revision,
                toRange(request.acceptedRange())));
    }

    @PostMapping("/devices/{id}/revisions/{revision}/revoke")
    public ApiDtos.DispatchView revoke(@PathVariable String id, @PathVariable long revision,
                                       @RequestBody ApiDtos.RevokeRequest request) {
        log.info("http revoke | deviceId={} | revision={} | owner={}", id, revision, request.owner());
        return toDispatchView(fleetService.revoke(id, request.owner(), revision));
    }

    @GetMapping("/devices/{id}/revisions")
    public List<ApiDtos.RevisionView> revisions(@PathVariable String id, @RequestParam String owner) {
        return fleetService.revisions(id, owner).stream().map(this::toRevisionView).toList();
    }

    // ---- 映射辅助 --------------------------------------------------------

    private CapabilityRange toRange(ApiDtos.RangeSpec spec) {
        if (spec == null) {
            return null;
        }
        return new CapabilityRange(Version.parse(spec.minFirmware()), Version.parse(spec.maxFirmware()),
                spec.requiredCapability());
    }

    private ApiDtos.DeviceView toDeviceView(Device device) {
        DesiredRevision latest = device.latestRevision();
        return new ApiDtos.DeviceView(
                device.id(), device.owner(), device.firmware().toString(),
                List.copyOf(device.capabilities()),
                device.livenessAt(registry.now()).name(),
                device.lastAcceptedReportAt() == null ? null : device.lastAcceptedReportAt().toString(),
                device.lastAcceptedSeq(),
                latest == null ? null : latest.revision(),
                latest == null ? null : latest.state().name());
    }

    private ApiDtos.DispatchView toDispatchView(DispatchResult r) {
        return new ApiDtos.DispatchView(r.deviceId(), r.revision(), r.outcome().name(),
                r.finalState().name(), r.detail(),
                r.decidedAt() == null ? null : r.decidedAt().toString());
    }

    private ApiDtos.RevisionView toRevisionView(DesiredRevision r) {
        return new ApiDtos.RevisionView(r.revision(), r.owner(), r.config(),
                r.state().name(),
                r.outcome() == null ? null : r.outcome().name(),
                r.detail(),
                r.createdAt().toString(),
                r.decidedAt() == null ? null : r.decidedAt().toString());
    }
}
