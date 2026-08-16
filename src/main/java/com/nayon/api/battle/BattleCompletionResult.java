package com.nayon.api.battle;

import com.nayon.api.economy.EconomySnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BattleCompletionResult(
        UUID battleId,
        String stageCode,
        BattleOutcome outcome,
        BattleRewardState rewardState,
        long gold,
        long accountExp,
        long totalAccountExp,
        List<String> anomalyReasons,
        EconomySnapshot economy,
        Instant completedAt,
        boolean replay) {

    public BattleCompletionResult {
        anomalyReasons = List.copyOf(anomalyReasons);
    }

    public BattleCompletionResult asReplay() {
        return new BattleCompletionResult(
                battleId, stageCode, outcome, rewardState, gold, accountExp,
                totalAccountExp, anomalyReasons, economy, completedAt, true);
    }
}
