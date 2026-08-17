package com.nayon.api.levelreward;

import com.nayon.api.subscription.PlayerSubscription;

import java.util.List;

public record LevelRewardList(
        int accountLevel,
        long totalAccountExp,
        List<PlayerSubscription> subscriptions,
        List<LevelRewardItem> rewards) {

    public LevelRewardList {
        subscriptions = List.copyOf(subscriptions);
        rewards = List.copyOf(rewards);
    }
}
