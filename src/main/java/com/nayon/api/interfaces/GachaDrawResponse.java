package com.nayon.api.interfaces;

import com.nayon.api.gacha.GachaDrawResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GachaDrawResponse(
        UUID drawId,
        String banner,
        String payment,
        long paymentAmount,
        List<GachaAwardResponse> results,
        int heroPity,
        int legendaryPity,
        EconomyResponse economy,
        Instant createdAt,
        boolean replay) {

    static GachaDrawResponse from(GachaDrawResult result) {
        return new GachaDrawResponse(
                result.drawId(), result.banner().name(), result.payment().name(),
                result.paymentAmount(),
                result.results().stream().map(GachaAwardResponse::from).toList(),
                result.pity().hero(), result.pity().legendary(),
                EconomyResponse.from(result.economy()), result.createdAt(), result.replay());
    }
}
