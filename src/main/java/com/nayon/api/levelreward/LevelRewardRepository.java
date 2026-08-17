package com.nayon.api.levelreward;

import com.nayon.api.subscription.PlayerSubscription;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LevelRewardRepository {
    long totalAccountExp(UUID accountId);

    List<LevelRewardItem> findAll(
            UUID accountId,
            int accountLevel,
            List<PlayerSubscription> subscriptions,
            Instant now);

    LevelRewardClaimResult claim(
            UUID accountId,
            UUID requestId,
            String requestHash,
            LevelRewardTrackCode trackCode,
            int requiredLevel,
            int accountLevel,
            Instant now);
}
