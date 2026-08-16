package com.nayon.api.battle.offline;

import com.nayon.api.battle.BattleStageCatalog;

import java.time.Instant;
import java.util.UUID;

public interface OfflineBattleRepository {
    OfflineBattleWindowResult sync(
            UUID accountId, UUID requestId, String requestHash,
            Instant now, Instant expiresAt,
            BattleStageCatalog.Configuration rules);

    OfflineBattleSubmissionResult submit(
            UUID accountId, UUID requestId, String requestHash,
            OfflineBattleSubmissionCommand command,
            OfflineBattleEvaluator evaluator,
            Instant now);
}
