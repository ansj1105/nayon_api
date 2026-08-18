package com.nayon.api.time;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

@Component
public class KstGameTimeCalculator {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ServerClock clock;

    public KstGameTimeCalculator(ServerClock clock) {
        this.clock = clock;
    }

    public ZonedDateTime now() {
        return clock.now().atZone(KST);
    }

    public RewardPeriod dailyPeriod() {
        LocalDate date = now().toLocalDate();
        return period("DAILY", date, date.atStartOfDay(KST), date.plusDays(1).atStartOfDay(KST));
    }

    public RewardPeriod weeklyPeriod() {
        LocalDate monday = now().toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return period("WEEKLY", monday, monday.atStartOfDay(KST),
                monday.plusWeeks(1).atStartOfDay(KST));
    }

    public ZonedDateTime expiresAt(Instant startedAt, Duration duration) {
        return startedAt.plus(duration).atZone(KST);
    }

    public Duration remainingUntil(Instant expiresAt) {
        Duration remaining = Duration.between(clock.now(), expiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public boolean isExpired(Instant expiresAt) {
        return !clock.now().isBefore(expiresAt);
    }

    private RewardPeriod period(
            String type,
            LocalDate key,
            ZonedDateTime startsAt,
            ZonedDateTime endsAt) {
        return new RewardPeriod(type, key, startsAt, endsAt, KST.getId());
    }
}
