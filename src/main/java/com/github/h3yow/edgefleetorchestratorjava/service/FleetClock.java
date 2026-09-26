package com.github.h3yow.edgefleetorchestratorjava.service;

import com.github.h3yow.edgefleetorchestratorjava.sim.DeviceSimulator;

import java.time.Clock;

/**
 * 统一时钟与本地模拟器装配：测试可注入可控时钟（MutableClock），
 * 使 TTL 过期、时间戳回退等场景完全可重复验证。
 */
@org.springframework.context.annotation.Configuration
public class FleetClock {

    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    public Clock clock() {
        return Clock.systemUTC();
    }

    @org.springframework.context.annotation.Bean
    public DeviceSimulator deviceSimulator(Clock clock) {
        return new DeviceSimulator(clock);
    }
}
