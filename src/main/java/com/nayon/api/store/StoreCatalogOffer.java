package com.nayon.api.store;

public record StoreCatalogOffer(
        String offerCode,
        String productId,
        String productType,
        String rewardAssetCode,
        long rewardAmount,
        int rewardVersion) {
}
