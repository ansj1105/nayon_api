package com.nayon.api.subscription;

public record SubscriptionVerificationResult(
        PlayerSubscription subscription,
        SubscriptionRewardGrant initialReward,
        boolean replay) {

    public SubscriptionVerificationResult asReplay() {
        return replay ? this : new SubscriptionVerificationResult(
                subscription.asReplay(), initialReward, true);
    }
}
