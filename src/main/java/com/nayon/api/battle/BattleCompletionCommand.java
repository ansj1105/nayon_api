package com.nayon.api.battle;

import java.math.BigDecimal;
import java.time.Instant;

public record BattleCompletionCommand(
        BattleOutcome outcome,
        int elapsedSeconds,
        int killCount,
        BigDecimal totalDamage,
        int reachedWave,
        Instant clientEndedAt) {
}
