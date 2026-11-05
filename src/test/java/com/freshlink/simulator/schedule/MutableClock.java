package com.freshlink.simulator.schedule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 테스트용 시계: 원하는 만큼 앞으로 돌린다 */
class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    MutableClock(ZonedDateTime start) {
        this.now = start.toInstant();
        this.zone = start.getZone();
    }

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
