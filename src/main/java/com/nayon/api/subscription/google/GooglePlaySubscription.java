package com.nayon.api.subscription.google;

import com.nayon.api.subscription.SubscriptionState;

import java.time.Instant;

public record GooglePlaySubscription(
        String productId,
        SubscriptionState state,
        String orderId,
        String obfuscatedAccountId,
        Instant startedAt,
        Instant expiresAt,
        boolean autoRenewing,
        boolean acknowledged,
        String linkedPurchaseToken) {
}
