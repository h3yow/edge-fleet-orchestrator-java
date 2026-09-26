package com.github.h3yow.edgefleetorchestratorjava.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 统一时钟，测试中可替换为可控时钟以保证可重复。 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
