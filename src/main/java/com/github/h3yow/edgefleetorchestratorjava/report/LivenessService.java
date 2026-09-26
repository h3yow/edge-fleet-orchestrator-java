package com.github.h3yow.edgefleetorchestratorjava.report;

import com.github.h3yow.edgefleetorchestratorjava.model.Device;
import com.github.h3yow.edgefleetorchestratorjava.model.DeviceStatus;
import com.github.h3yow.edgefleetorchestratorjava.registry.DeviceRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 存活维护服务。
 *
 * <p>设备必须通过被接受的周期上报证明存活：自最近一次被接受的上报（observedAt）起，
 * 超过心跳 TTL 仍无新上报的设备，在 {@link #sweep()} 或 {@link #observe(Device)} 时
 * 转为 OFFLINE。拒绝/忽略的上报不刷新该时间，因此设备无法无限期保持可用。
 *
 * <p>TTL 通过 {@code edge.heartbeat-ttl} 配置，默认 30 秒，便于测试用小步时钟验证。
 */
@Service
public class LivenessService {

    private static final Logger log = LoggerFactory.getLogger(LivenessService.class);

    private final DeviceRegistry registry;
    private final Clock clock;
    private final Duration heartbeatTtl;

    public LivenessService(DeviceRegistry registry, Clock clock,
                           @Value("${edge.heartbeat-ttl:30s}") Duration heartbeatTtl) {
        this.registry = registry;
        this.clock = clock;
        this.heartbeatTtl = heartbeatTtl;
    }

    /**
     * 扫描全部注册设备，将过期者标记 OFFLINE。
     *
     * @return 本次扫描中由 ONLINE 变为 OFFLINE 的设备数
     */
    public int sweep() {
        Instant now = clock.instant();
        Collection<Device> all = snapshot();
        AtomicInteger expired = new AtomicInteger();
        for (Device device : all) {
            if (markIfExpired(device, now)) {
                expired.incrementAndGet();
            }
        }
        log.info("liveness sweep done: now={} total={} newlyExpired={} ttl={}s",
                now, all.size(), expired.get(), heartbeatTtl.toSeconds());
        return expired.get();
    }

    /** 查询前按需评估单台设备的实时存活状态（不等待周期扫描）。 */
    public Device observe(Device device) {
        markIfExpired(device, clock.instant());
        return device;
    }

    public Instant now() {
        return clock.instant();
    }

    public Duration heartbeatTtl() {
        return heartbeatTtl;
    }

    private boolean markIfExpired(Device device, Instant now) {
        Instant lastSeen = device.getLastSeenAt();
        boolean neverReported = lastSeen == null;
        boolean expired = lastSeen != null && lastSeen.plus(heartbeatTtl).isBefore(now);
        if ((neverReported || expired) && device.getStatus() == DeviceStatus.ONLINE) {
            device.setStatus(DeviceStatus.OFFLINE);
            log.warn("device marked OFFLINE: deviceId={} lastSeen={} now={} ttl={}s reason={}",
                    device.getDeviceId(), lastSeen, now, heartbeatTtl.toSeconds(),
                    neverReported ? "never reported" : "heartbeat expired");
            return true;
        }
        return false;
    }

    private Collection<Device> snapshot() {
        // 注册表当前只暴露 find/size；通过 size 触发不了遍历，这里借助 DeviceRegistry 新增的 all()
        // 见 DeviceRegistry#all
        return registry.all();
    }
}
