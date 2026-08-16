package com.nayon.api.interfaces;

import com.nayon.api.battle.BattleHistoryPage;

import java.util.List;
import java.util.UUID;

public record BattleHistoryResponse(
        List<BattleCompletionResponse> battles,
        UUID nextCursor) {
    static BattleHistoryResponse from(BattleHistoryPage page) {
        return new BattleHistoryResponse(
                page.battles().stream().map(BattleCompletionResponse::from).toList(),
                page.nextCursor());
    }
}
