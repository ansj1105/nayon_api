package com.nayon.api.battle.offline;

import com.nayon.api.battle.BattleOutcome;

import java.math.BigDecimal;
import java.util.UUID;

public record OfflineBattleRunCommand(
        UUID runId,
        String stageCode,
        BattleOutcome outcome,
        int elapsedSeconds,
        int killCount,
        BigDecimal totalDamage,
        int reachedWave) {
}
