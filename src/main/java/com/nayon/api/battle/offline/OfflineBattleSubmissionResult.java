package com.nayon.api.battle.offline;

import com.nayon.api.battle.BattleRewardState;
import com.nayon.api.economy.EconomySnapshot;

import java.util.List;
import java.util.UUID;

public record OfflineBattleSubmissionResult(
        UUID submissionId,
        BattleRewardState rewardState,
        List<UUID> acceptedRunIds,
        List<String> anomalyReasons,
        long accountExp,
        EconomySnapshot economy,
        boolean replay) {
    public OfflineBattleSubmissionResult {
        acceptedRunIds = List.copyOf(acceptedRunIds);
        anomalyReasons = List.copyOf(anomalyReasons);
    }

    public OfflineBattleSubmissionResult asReplay() {
        return new OfflineBattleSubmissionResult(
                submissionId, rewardState, acceptedRunIds,
                anomalyReasons, accountExp, economy, true);
    }
}
