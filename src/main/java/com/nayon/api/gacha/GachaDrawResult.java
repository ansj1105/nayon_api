package com.nayon.api.gacha;

import com.nayon.api.economy.EconomySnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GachaDrawResult(
        UUID drawId,
        GachaBanner banner,
        GachaPayment payment,
        long paymentAmount,
        List<GachaAward> results,
        GachaPity pity,
        EconomySnapshot economy,
        Instant createdAt,
        boolean replay) {

    public GachaDrawResult {
        results = List.copyOf(results);
    }

    public GachaDrawResult asReplay() {
        return new GachaDrawResult(
                drawId, banner, payment, paymentAmount, results,
                pity, economy, createdAt, true);
    }
}
