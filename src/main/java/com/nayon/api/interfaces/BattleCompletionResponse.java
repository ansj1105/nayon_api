package com.nayon.api.interfaces;

import com.nayon.api.battle.BattleCompletionResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BattleCompletionResponse(
        UUID battleId,
        String stageCode,
        String outcome,
        String rewardState,
        long gold,
        long accountExp,
        long totalAccountExp,
        long randomScroll,
        long levelUpCoupon,
        List<String> anomalyReasons,
        EconomyResponse economy,
        Instant completedAt,
        boolean replay) {
    static BattleCompletionResponse from(BattleCompletionResult result) {
        return new BattleCompletionResponse(
                result.battleId(), result.stageCode(), result.outcome().name(),
                result.rewardState().name(), result.gold(), result.accountExp(),
                result.totalAccountExp(), result.randomScroll(),
                result.levelUpCoupon(), result.anomalyReasons(),
                EconomyResponse.from(result.economy()), result.completedAt(),
                result.replay());
    }
}
