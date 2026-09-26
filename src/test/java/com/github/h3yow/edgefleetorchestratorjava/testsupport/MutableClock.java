package com.github.h3yow.edgefleetorchestratorjava.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：初始时刻固定，可显式推进，保证 TTL 过期 / 时间戳回退等验证可重复。
 */
public class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this.now = start;
        this.zone = ZoneOffset.UTC;
    }

    public static MutableClock startAt(String iso) {
        return new MutableClock(Instant.parse(iso));
    }

    public void advance(Duration d) {
        this.now = now.plus(d);
    }

    public void set(Instant instant) {
        this.now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
        return now;
    }
}
