package com.nayon.api.store.google;

import java.time.Instant;
import java.util.List;

public record GooglePlayPurchase(
        List<String> productIds,
        GooglePlayPurchaseState state,
        String orderId,
        String obfuscatedAccountId,
        Instant purchaseTime,
        boolean acknowledged) {
}
