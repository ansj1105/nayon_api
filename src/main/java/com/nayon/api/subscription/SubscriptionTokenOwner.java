package com.nayon.api.subscription;

import java.util.UUID;

public record SubscriptionTokenOwner(
        UUID accountId,
        String productId,
        String purchaseTokenHash) {
}
