package com.github.h3yow.edgefleetorchestratorjava.api;

import com.github.h3yow.edgefleetorchestratorjava.DeviceNotFoundException;
import com.github.h3yow.edgefleetorchestratorjava.DeviceNotFoundException;
import com.github.h3yow.edgefleetorchestratorjava.model.Device;
import com.github.h3yow.edgefleetorchestratorjava.registry.DeviceRegistry;
import com.github.h3yow.edgefleetorchestratorjava.report.LivenessService;
import java.util.Set;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 设备注册与查询入口。 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceRegistry registry;
    private final LivenessService liveness;

    public DeviceController(DeviceRegistry registry, LivenessService liveness) {
        this.registry = registry;
        this.liveness = liveness;
    }

    public record RegisterRequest(String deviceId, String ownerId, String firmwareVersion,
                                  Set<String> capabilities) {
    }

    @PostMapping
    public Device register(@RequestBody RegisterRequest request) {
        return registry.register(request.deviceId(), request.ownerId(),
                request.firmwareVersion(), request.capabilities());
    }

    @org.springframework.web.bind.annotation.GetMapping("/{deviceId}")
    public Device get(@PathVariable String deviceId) {
        Device device = registry.find(deviceId);
        if (device == null) {
            throw new DeviceNotFoundException(deviceId);
        }
        return device;
    }
}
