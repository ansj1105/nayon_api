package com.nayon.api.store;

public record StorePurchaseCommand(String productId, String purchaseToken) {
}
