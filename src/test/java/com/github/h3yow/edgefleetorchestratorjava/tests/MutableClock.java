package com.github.h3yow.edgefleetorchestratorjava.tests;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 可控时钟：测试中手动推进时间，保证 TTL / 乱序场景可重复、无真实等待。 */
public final class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    public static MutableClock startAt(String iso) {
        return new MutableClock(Instant.parse(iso));
    }

    public void advanceSeconds(long seconds) {
        instant = instant.plusSeconds(seconds);
    }

    public void setInstant(Instant value) {
        this.instant = value;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
