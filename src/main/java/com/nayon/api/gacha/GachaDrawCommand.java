package com.nayon.api.gacha;

public record GachaDrawCommand(
        GachaBanner banner,
        GachaPayment payment,
        int count) {
}
