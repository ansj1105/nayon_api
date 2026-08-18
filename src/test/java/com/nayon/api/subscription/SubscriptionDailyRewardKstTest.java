package com.nayon.api.subscription;

import com.nayon.api.time.KstGameTimeCalculator;
import com.nayon.api.time.ServerClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionDailyRewardKstTest {

    @Test
    void rewardDateChangesAtKstMidnight() {
        var repository = new CapturingRepository();

        serviceAt(repository, "2026-08-18T14:59:59Z")
                .claim(UUID.randomUUID(), UUID.randomUUID(),
                        SubscriptionPlanCode.MONTHLY_GROWTH);
        assertThat(repository.rewardDate).isEqualTo(LocalDate.of(2026, 8, 18));

        serviceAt(repository, "2026-08-18T15:00:00Z")
                .claim(UUID.randomUUID(), UUID.randomUUID(),
                        SubscriptionPlanCode.MONTHLY_GROWTH);
        assertThat(repository.rewardDate).isEqualTo(LocalDate.of(2026, 8, 19));
    }

    private SubscriptionDailyRewardService serviceAt(
            CapturingRepository repository, String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new SubscriptionDailyRewardService(
                repository,
                new KstGameTimeCalculator(new ServerClock(clock)));
    }

    private static final class CapturingRepository
            implements SubscriptionRewardRepository {
        private LocalDate rewardDate;

        @Override
        public SubscriptionRewardGrant grantInitialIfEligible(
                UUID accountId, UUID subscriptionId, UUID requestId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SubscriptionDailyRewardResult claimDaily(
                UUID accountId,
                UUID requestId,
                String requestHash,
                SubscriptionPlanCode planCode,
                LocalDate rewardDate,
                Instant now) {
            this.rewardDate = rewardDate;
            return null;
        }
    }
}
