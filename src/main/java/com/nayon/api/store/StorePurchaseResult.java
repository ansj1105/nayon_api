package com.nayon.api.store;

public record StorePurchaseResult(
        StorePurchaseReceipt receipt,
        FirstPurchaseReward firstPurchaseReward,
        boolean replay) {

    public StorePurchaseResult(StorePurchaseReceipt receipt, boolean replay) {
        this(receipt, null, replay);
    }
}
