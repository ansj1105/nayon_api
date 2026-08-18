package com.nayon.api.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class KstGameTimeCalculatorTest {

    @Test
    void dailyPeriodChangesAtKstMidnight() {
        var before = calculatorAt("2026-08-18T14:59:59Z").dailyPeriod();
        var after = calculatorAt("2026-08-18T15:00:00Z").dailyPeriod();

        assertThat(before.periodKey()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(before.endsAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(before.endsAt().toInstant()).isEqualTo(Instant.parse("2026-08-18T15:00:00Z"));
        assertThat(after.periodKey()).isEqualTo(LocalDate.of(2026, 8, 19));
    }

    @Test
    void weeklyPeriodChangesAtKstMondayMidnight() {
        var sunday = calculatorAt("2026-08-23T14:59:59Z").weeklyPeriod();
        var monday = calculatorAt("2026-08-23T15:00:00Z").weeklyPeriod();

        assertThat(sunday.periodKey()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(sunday.endsAt().toInstant()).isEqualTo(Instant.parse("2026-08-23T15:00:00Z"));
        assertThat(monday.periodKey()).isEqualTo(LocalDate.of(2026, 8, 24));
    }

    @Test
    void expiryAndRemainingUseTheSameServerClock() {
        var calculator = calculatorAt("2026-08-19T03:00:00Z");
        Instant expiresAt = Instant.parse("2026-08-19T03:10:00Z");

        assertThat(calculator.expiresAt(
                Instant.parse("2026-08-19T03:00:00Z"), Duration.ofMinutes(10)).toInstant())
                .isEqualTo(expiresAt);
        assertThat(calculator.remainingUntil(expiresAt)).isEqualTo(Duration.ofMinutes(10));
        assertThat(calculator.isExpired(expiresAt)).isFalse();
    }

    @Test
    void expiredRemainingNeverBecomesNegative() {
        var calculator = calculatorAt("2026-08-19T03:00:00Z");
        Instant expiredAt = Instant.parse("2026-08-19T02:59:59Z");

        assertThat(calculator.remainingUntil(expiredAt)).isZero();
        assertThat(calculator.isExpired(expiredAt)).isTrue();
    }

    private KstGameTimeCalculator calculatorAt(String instant) {
        return new KstGameTimeCalculator(new ServerClock(
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)));
    }
}
