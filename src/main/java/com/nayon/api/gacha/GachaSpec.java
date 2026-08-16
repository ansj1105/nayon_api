package com.nayon.api.gacha;

public record GachaSpec(
        GachaBanner banner,
        GachaPayment payment,
        int count,
        String assetType,
        String assetCode,
        long amount) {
}
