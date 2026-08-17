package com.nayon.api.interfaces;

import com.nayon.api.subscription.PlayerSubscription;
import com.nayon.api.subscription.SubscriptionRewardGrant;
import com.nayon.api.subscription.SubscriptionVerificationResult;

import java.time.Instant;
import java.util.List;

public record SubscriptionResponse(
        String planCode,
        String state,
        boolean entitled,
        boolean autoRenewing,
        Instant startedAt,
        Instant expiresAt,
        Instant lastVerifiedAt) {

    public static SubscriptionResponse from(
            PlayerSubscription subscription, Instant serverTime) {
        return new SubscriptionResponse(
                subscription.planCode().name(), subscription.state().name(),
                subscription.entitled(serverTime), subscription.autoRenewing(),
                subscription.startedAt(), subscription.expiresAt(),
                subscription.lastVerifiedAt());
    }

    public record ListResponse(
            Instant serverTime,
            List<SubscriptionResponse> subscriptions) {
    }

    public record VerifyResponse(
            SubscriptionResponse subscription,
            Reward initialReward,
            boolean replay) {

        public static VerifyResponse from(
                SubscriptionVerificationResult result, Instant serverTime) {
            return new VerifyResponse(
                    SubscriptionResponse.from(result.subscription(), serverTime),
                    Reward.from(result.initialReward()), result.replay());
        }
    }

    public record Reward(String assetCode, long amount, long balance) {
        private static Reward from(SubscriptionRewardGrant grant) {
            return grant == null ? null
                    : new Reward(grant.assetCode(), grant.amount(), grant.balance());
        }
    }
}
