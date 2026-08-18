package com.nayon.api.weeklygift;

public record WeeklyGiftReward(
        String assetType,
        String assetCode,
        long amount) {
}
