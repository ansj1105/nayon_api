package com.nayon.api.store;

import com.nayon.api.store.google.GooglePlayPurchase;

import java.util.UUID;

public interface StorePurchaseRepository {
    StorePurchaseReceipt begin(
            UUID accountId,
            UUID requestId,
            String requestHash,
            String productId,
            String purchaseToken,
            String purchaseTokenHash);

    StorePurchaseReceipt grant(
            UUID receiptId,
            UUID accountId,
            GooglePlayPurchase purchase);

    StorePurchaseReceipt reject(UUID receiptId, String rejectionCode);

    void markVerificationFailure(UUID receiptId, String failureCode);

}
