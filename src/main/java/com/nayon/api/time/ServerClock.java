package com.nayon.api.time;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class ServerClock {
    private final Clock clock;

    public ServerClock() {
        this(Clock.systemUTC());
    }

    public ServerClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }
}
