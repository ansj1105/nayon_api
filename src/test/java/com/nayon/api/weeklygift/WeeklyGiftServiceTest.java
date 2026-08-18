package com.nayon.api.weeklygift;

import com.nayon.api.economy.EconomySnapshot;
import com.nayon.api.time.KstGameTimeCalculator;
import com.nayon.api.time.RewardPeriod;
import com.nayon.api.time.ServerClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyGiftServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");

    @Test
    void getDoesNotRecordLoginDay() {
        var repository = new FakeRepository();
        var service = serviceAt(repository, "2026-08-17T03:00:00Z");

        WeeklyGiftState state = service.get(ACCOUNT_ID);

        assertThat(state.loginDays()).isZero();
        assertThat(repository.checkInCalls).isZero();
    }

    @Test
    void sameKstDateChecksInOnlyOnce() {
        var repository = new FakeRepository();
        var service = serviceAt(repository, "2026-08-17T03:00:00Z");

        service.checkIn(ACCOUNT_ID);
        WeeklyGiftState state = service.checkIn(ACCOUNT_ID);

        assertThat(state.loginDays()).isEqualTo(1);
    }

    @Test
    void thirdDistinctKstDateBecomesClaimable() {
        var repository = new FakeRepository();

        serviceAt(repository, "2026-08-17T03:00:00Z").checkIn(ACCOUNT_ID);
        serviceAt(repository, "2026-08-18T03:00:00Z").checkIn(ACCOUNT_ID);
        WeeklyGiftState state = serviceAt(
                repository, "2026-08-19T03:00:00Z").checkIn(ACCOUNT_ID);

        assertThat(state.loginDays()).isEqualTo(3);
        assertThat(state.claimable()).isTrue();
    }

    @Test
    void mondayStartsASeparateWeek() {
        var repository = new FakeRepository();

        serviceAt(repository, "2026-08-23T03:00:00Z").checkIn(ACCOUNT_ID);
        WeeklyGiftState state = serviceAt(
                repository, "2026-08-23T15:00:00Z").checkIn(ACCOUNT_ID);

        assertThat(state.weekStart()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(state.loginDays()).isEqualTo(1);
    }

    @Test
    void claimRequiresThreeDistinctDates() {
        var repository = new FakeRepository();
        var service = serviceAt(repository, "2026-08-17T03:00:00Z");
        service.checkIn(ACCOUNT_ID);

        assertThatThrownBy(() -> service.claim(ACCOUNT_ID, UUID.randomUUID()))
                .isInstanceOf(WeeklyGiftException.class)
                .extracting("code")
                .isEqualTo("WEEKLY_GIFT_NOT_ELIGIBLE");
    }

    @Test
    void claimDoesNotChangeStateWhenRewardIsNotConfigured() {
        var repository = new FakeRepository();
        checkInThreeDays(repository);

        assertThatThrownBy(() -> serviceAt(
                repository, "2026-08-19T03:00:00Z")
                .claim(ACCOUNT_ID, UUID.randomUUID()))
                .isInstanceOf(WeeklyGiftException.class)
                .extracting("code")
                .isEqualTo("WEEKLY_GIFT_REWARD_NOT_CONFIGURED");
        assertThat(repository.claimed).isFalse();
    }

    @Test
    void configuredRewardCanBeClaimedAfterThreeDays() {
        var repository = new FakeRepository();
        repository.reward = new WeeklyGiftReward("CURRENCY", "DIAMOND", 1);
        checkInThreeDays(repository);

        WeeklyGiftState state = serviceAt(repository, "2026-08-19T03:00:00Z")
                .claim(ACCOUNT_ID, UUID.randomUUID());

        assertThat(state.claimed()).isTrue();
        assertThat(state.reward()).isEqualTo(repository.reward);
    }

    private void checkInThreeDays(FakeRepository repository) {
        serviceAt(repository, "2026-08-17T03:00:00Z").checkIn(ACCOUNT_ID);
        serviceAt(repository, "2026-08-18T03:00:00Z").checkIn(ACCOUNT_ID);
        serviceAt(repository, "2026-08-19T03:00:00Z").checkIn(ACCOUNT_ID);
    }

    private WeeklyGiftService serviceAt(FakeRepository repository, String instant) {
        var clock = new ServerClock(Clock.fixed(
                Instant.parse(instant), ZoneOffset.UTC));
        return new WeeklyGiftService(repository, new KstGameTimeCalculator(clock));
    }

    private static final class FakeRepository implements WeeklyGiftRepository {
        private final Map<LocalDate, Set<LocalDate>> days = new HashMap<>();
        private int checkInCalls;
        private boolean claimed;
        private WeeklyGiftReward reward;

        @Override
        public WeeklyGiftState get(UUID accountId, RewardPeriod period, Instant now) {
            return state(accountId, period, now, false);
        }

        @Override
        public WeeklyGiftState checkIn(
                UUID accountId, RewardPeriod period, LocalDate loginDate, Instant now) {
            checkInCalls++;
            days.computeIfAbsent(period.periodKey(), ignored -> new HashSet<>())
                    .add(loginDate);
            return state(accountId, period, now, false);
        }

        @Override
        public WeeklyGiftState claim(
                UUID accountId, UUID requestId, RewardPeriod period, Instant now) {
            int count = days.getOrDefault(period.periodKey(), Set.of()).size();
            if (count < 3) {
                throw new WeeklyGiftException(
                        "WEEKLY_GIFT_NOT_ELIGIBLE", "Three login days are required.");
            }
            if (reward == null) {
                throw new WeeklyGiftException(
                        "WEEKLY_GIFT_REWARD_NOT_CONFIGURED", "Reward is not configured.");
            }
            claimed = true;
            return state(accountId, period, now, false);
        }

        private WeeklyGiftState state(
                UUID accountId, RewardPeriod period, Instant now, boolean replay) {
            int count = days.getOrDefault(period.periodKey(), Set.of()).size();
            EconomySnapshot economy = claimed
                    ? new EconomySnapshot(accountId, Map.of("DIAMOND", 1L),
                    Map.of(), java.util.List.of(), true)
                    : null;
            return WeeklyGiftState.create(
                    now.atZone(ZoneId.of("Asia/Seoul")), period, count,
                    claimed, reward, economy, replay);
        }
    }
}
