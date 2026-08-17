package com.nayon.api.interfaces;

import com.nayon.api.subscription.SubscriptionCatalog;

import java.util.List;

public record SubscriptionCatalogResponse(
        String platform,
        String obfuscatedAccountId,
        List<Plan> plans) {

    public static SubscriptionCatalogResponse from(SubscriptionCatalog catalog) {
        return new SubscriptionCatalogResponse(
                catalog.platform(), catalog.obfuscatedAccountId(),
                catalog.plans().stream().map(plan -> new Plan(
                        plan.planCode().name(), plan.productId(), "SUBSCRIPTION",
                        plan.rewardTrackCode(),
                        plan.benefits().stream().map(benefit -> new Benefit(
                                benefit.code(), benefit.value(), benefit.version()))
                                .toList())).toList());
    }

    public record Plan(
            String planCode,
            String productId,
            String productType,
            String rewardTrackCode,
            List<Benefit> benefits) {
    }

    public record Benefit(String code, long value, int version) {
    }
}
