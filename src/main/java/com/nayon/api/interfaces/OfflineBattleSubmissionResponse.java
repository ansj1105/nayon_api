package com.nayon.api.interfaces;

import com.nayon.api.battle.BattleRewardState;
import com.nayon.api.battle.offline.OfflineBattleSubmissionResult;

import java.util.List;
import java.util.UUID;

public record OfflineBattleSubmissionResponse(
        UUID submissionId,
        BattleRewardState rewardState,
        List<UUID> acceptedRunIds,
        List<String> anomalyReasons,
        long accountExp,
        EconomyResponse economy,
        boolean replay) {
    static OfflineBattleSubmissionResponse from(OfflineBattleSubmissionResult result) {
        return new OfflineBattleSubmissionResponse(
                result.submissionId(), result.rewardState(), result.acceptedRunIds(),
                result.anomalyReasons(), result.accountExp(),
                EconomyResponse.from(result.economy()),
                result.replay());
    }
}
