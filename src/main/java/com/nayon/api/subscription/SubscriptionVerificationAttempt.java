package com.nayon.api.subscription;

import java.util.UUID;

public record SubscriptionVerificationAttempt(
        UUID subscriptionId,
        SubscriptionPlanCode planCode,
        String productId,
        boolean replay,
        SubscriptionVerificationResult result) {
}
