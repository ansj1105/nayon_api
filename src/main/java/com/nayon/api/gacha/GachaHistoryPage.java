package com.nayon.api.gacha;

import java.util.List;
import java.util.UUID;

public record GachaHistoryPage(
        List<GachaDrawResult> draws,
        UUID nextCursor) {

    public GachaHistoryPage {
        draws = List.copyOf(draws);
    }
}
