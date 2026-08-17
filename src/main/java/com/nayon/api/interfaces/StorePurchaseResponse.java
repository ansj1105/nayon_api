package com.nayon.api.interfaces;

import com.nayon.api.store.StorePurchaseReceipt;
import com.nayon.api.store.StorePurchaseResult;

import java.time.Instant;
import java.util.UUID;

public record StorePurchaseResponse(
        UUID receiptId,
        String state,
        String offerCode,
        String productId,
        String fulfillmentType,
        Reward reward,
        Long totalAssetBalance,
        String googleOrderId,
        Instant grantedAt,
        String rejectionCode,
        FirstPurchaseRewardResponse firstPurchaseReward,
        boolean replay) {

    public static StorePurchaseResponse from(StorePurchaseResult result) {
        StorePurchaseReceipt receipt = result.receipt();
        Reward reward = receipt.rewardAssetCode() == null
                ? null : new Reward(receipt.rewardAssetCode(),
                        receipt.rewardAmount(), receipt.rewardVersion());
        return new StorePurchaseResponse(
                receipt.id(), receipt.state().name(), receipt.offerCode(),
                receipt.productId(), receipt.fulfillmentType(), reward,
                receipt.totalAssetBalance(),
                receipt.googleOrderId(), receipt.grantedAt(),
                receipt.rejectionCode(),
                result.firstPurchaseReward() == null ? null
                        : FirstPurchaseRewardResponse.from(result.firstPurchaseReward()),
                result.replay());
    }

    public record Reward(String assetCode, long amount, int version) {
    }
}
