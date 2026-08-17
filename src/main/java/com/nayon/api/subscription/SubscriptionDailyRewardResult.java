package com.nayon.api.subscription;

import com.nayon.api.economy.EconomySnapshot;

import java.time.LocalDate;
import java.util.UUID;

public record SubscriptionDailyRewardResult(
        UUID grantId,
        SubscriptionPlanCode planCode,
        LocalDate rewardDate,
        SubscriptionRewardGrant reward,
        EconomySnapshot economy,
        boolean replay) {

    public SubscriptionDailyRewardResult asReplay() {
        return replay ? this : new SubscriptionDailyRewardResult(
                grantId, planCode, rewardDate, reward, economy, true);
    }
}
