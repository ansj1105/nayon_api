package com.nayon.api.battle;

import java.util.List;
import java.util.UUID;

public record BattleHistoryPage(
        List<BattleCompletionResult> battles,
        UUID nextCursor) {
    public BattleHistoryPage {
        battles = List.copyOf(battles);
    }
}
