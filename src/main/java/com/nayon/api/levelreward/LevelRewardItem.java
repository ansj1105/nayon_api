package com.nayon.api.levelreward;

public record LevelRewardItem(
        int version,
        LevelRewardTrackCode trackCode,
        int requiredLevel,
        String assetType,
        String assetCode,
        long amount,
        boolean claimed,
        boolean claimable) {
}
