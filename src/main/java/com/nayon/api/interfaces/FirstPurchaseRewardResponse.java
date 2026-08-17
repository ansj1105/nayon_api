package com.nayon.api.interfaces;

import com.nayon.api.store.FirstPurchaseReward;

import java.time.Instant;
import java.util.UUID;

public record FirstPurchaseRewardResponse(
        String status,
        UUID qualifyingReceiptId,
        Integer rewardVersion,
        String equipmentCatalogVersion,
        Rewards rewards,
        EconomyResponse economy,
        Instant grantedAt) {

    public static FirstPurchaseRewardResponse notGranted() {
        return new FirstPurchaseRewardResponse(
                "NOT_GRANTED", null, null, null, null, null, null);
    }

    public static FirstPurchaseRewardResponse from(FirstPurchaseReward reward) {
        return new FirstPurchaseRewardResponse(
                "GRANTED",
                reward.qualifyingReceiptId(),
                reward.rewardVersion(),
                reward.equipmentCatalogVersion(),
                new Rewards(
                        new Equipment(
                                reward.equipmentId(), reward.equipmentCode(),
                                reward.equipmentGrade()),
                        new Currency(reward.diamondAmount(), reward.diamondBalance()),
                        new Currency(reward.goldAmount(), reward.goldBalance())),
                EconomyResponse.from(reward.economy()),
                reward.grantedAt());
    }

    public record Rewards(
            Equipment equipment,
            Currency diamond,
            Currency gold) {
    }

    public record Equipment(UUID id, String equipmentCode, String grade) {
    }

    public record Currency(long amount, long balance) {
    }
}
