package com.nayon.api.subscription;

import java.util.List;

public record SubscriptionPlan(
        SubscriptionPlanCode planCode,
        String productId,
        String rewardTrackCode,
        List<SubscriptionBenefit> benefits) {

    public SubscriptionPlan {
        benefits = List.copyOf(benefits);
    }
}
