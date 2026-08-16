package com.nayon.api.battle;

import java.time.Instant;
import java.util.UUID;

public interface BattleRepository {
    BattleSessionResult start(
            UUID accountId,
            UUID requestId,
            String requestHash,
            BattleStartCommand command,
            BattleStageLimit stage,
            BattleStageCatalog.Configuration configuration,
            Instant now);

    BattleCompletionResult complete(
            UUID accountId,
            UUID battleId,
            UUID requestId,
            String requestHash,
            BattleCompletionCommand command,
            BattleAnomalyEvaluator evaluator,
            BattleStageCatalog.Configuration configuration,
            Instant now);

    BattleHistoryPage history(UUID accountId, UUID before, int limit);
}
