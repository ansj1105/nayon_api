package com.nayon.api.subscription;

import java.util.List;

public record SubscriptionCatalog(
        String platform,
        String obfuscatedAccountId,
        List<SubscriptionPlan> plans) {

    public SubscriptionCatalog {
        plans = List.copyOf(plans);
    }
}
