package com.nayon.api.store;

import com.nayon.api.economy.EconomySnapshot;

import java.time.Instant;
import java.util.UUID;

public record FirstPurchaseReward(
        UUID id,
        UUID accountId,
        UUID qualifyingReceiptId,
        int rewardVersion,
        String equipmentCatalogVersion,
        UUID equipmentId,
        String equipmentCode,
        String equipmentGrade,
        long diamondAmount,
        long goldAmount,
        long diamondBalance,
        long goldBalance,
        Instant grantedAt,
        EconomySnapshot economy) {
}
