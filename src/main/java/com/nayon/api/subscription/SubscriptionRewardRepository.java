package com.nayon.api.subscription;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface SubscriptionRewardRepository {
    SubscriptionRewardGrant grantInitialIfEligible(
            UUID accountId,
            UUID subscriptionId,
            UUID requestId,
            Instant now);

    SubscriptionDailyRewardResult claimDaily(
            UUID accountId,
            UUID requestId,
            String requestHash,
            SubscriptionPlanCode planCode,
            LocalDate rewardDate,
            Instant now);
}
