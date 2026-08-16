package com.nayon.api.interfaces;

import com.nayon.api.gacha.GachaHistoryPage;

import java.util.List;
import java.util.UUID;

public record GachaHistoryResponse(
        List<GachaDrawResponse> draws,
        UUID nextCursor) {

    static GachaHistoryResponse from(GachaHistoryPage page) {
        return new GachaHistoryResponse(
                page.draws().stream().map(GachaDrawResponse::from).toList(),
                page.nextCursor());
    }
}
