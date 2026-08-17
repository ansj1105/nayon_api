package com.nayon.api.levelreward;

import com.nayon.api.economy.EconomySnapshot;

import java.util.UUID;

public record LevelRewardClaimResult(
        UUID claimId,
        LevelRewardItem reward,
        EconomySnapshot economy,
        boolean replay) {

    public LevelRewardClaimResult asReplay() {
        return replay ? this : new LevelRewardClaimResult(
                claimId, reward, economy, true);
    }
}
