package com.nayon.api.store;

import java.time.Instant;
import java.util.UUID;

public record StorePurchaseReceipt(
        UUID id,
        UUID accountId,
        UUID requestId,
        String requestHash,
        StorePurchaseState state,
        String offerCode,
        String productId,
        UUID productVersionId,
        int rewardVersion,
        String fulfillmentType,
        String rewardAssetCode,
        Long rewardAmount,
        String purchaseToken,
        String googleOrderId,
        Instant googlePurchaseTime,
        Long totalAssetBalance,
        String rejectionCode,
        String lastFailureCode,
        Instant grantedAt,
        boolean replay) {

    public StorePurchaseReceipt asReplay() {
        return replay ? this : new StorePurchaseReceipt(
                id, accountId, requestId, requestHash, state, offerCode,
                productId, productVersionId, rewardVersion, fulfillmentType, rewardAssetCode,
                rewardAmount, purchaseToken, googleOrderId, googlePurchaseTime,
                totalAssetBalance, rejectionCode, lastFailureCode,
                grantedAt, true);
    }
}
