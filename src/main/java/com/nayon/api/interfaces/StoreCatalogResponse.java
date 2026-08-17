package com.nayon.api.interfaces;

import com.nayon.api.store.StoreCatalog;

import java.util.List;

public record StoreCatalogResponse(
        String platform,
        String obfuscatedAccountId,
        List<Offer> offers) {

    public static StoreCatalogResponse from(StoreCatalog catalog) {
        return new StoreCatalogResponse(
                catalog.platform().name(),
                catalog.obfuscatedAccountId(),
                catalog.offers().stream().map(offer -> new Offer(
                        offer.offerCode(),
                        offer.productId(),
                        offer.productType(),
                        new Reward(offer.rewardAssetCode(), offer.rewardAmount(),
                                offer.rewardVersion())))
                        .toList());
    }

    public record Offer(
            String offerCode,
            String productId,
            String productType,
            Reward reward) {
    }

    public record Reward(String assetCode, long amount, int version) {
    }
}
