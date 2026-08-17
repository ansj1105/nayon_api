package com.nayon.api.interfaces;

import com.nayon.api.levelreward.LevelRewardClaimResult;
import com.nayon.api.levelreward.LevelRewardItem;
import com.nayon.api.levelreward.LevelRewardList;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LevelRewardResponse {

    private LevelRewardResponse() {
    }

    public record Item(
            int version,
            String trackCode,
            int requiredLevel,
            String assetType,
            String assetCode,
            long amount,
            boolean claimed,
            boolean claimable) {

        static Item from(LevelRewardItem value) {
            return new Item(value.version(), value.trackCode().name(),
                    value.requiredLevel(), value.assetType(), value.assetCode(),
                    value.amount(), value.claimed(), value.claimable());
        }
    }

    public record ListResponse(
            int accountLevel,
            long totalAccountExp,
            List<SubscriptionResponse> subscriptions,
            List<Item> rewards) {

        static ListResponse from(LevelRewardList value, Instant now) {
            return new ListResponse(
                    value.accountLevel(), value.totalAccountExp(),
                    value.subscriptions().stream()
                            .map(subscription -> SubscriptionResponse.from(
                                    subscription, now)).toList(),
                    value.rewards().stream().map(Item::from).toList());
        }
    }

    public record ClaimResponse(
            UUID claimId,
            Item reward,
            EconomyResponse economy,
            boolean replay) {

        static ClaimResponse from(LevelRewardClaimResult value) {
            return new ClaimResponse(
                    value.claimId(), Item.from(value.reward()),
                    EconomyResponse.from(value.economy()), value.replay());
        }
    }
}
