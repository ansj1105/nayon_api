package com.nayon.api.interfaces;

import com.nayon.api.battle.BattleCompletionCommand;
import com.nayon.api.battle.BattleOutcome;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record BattleCompleteRequest(
        @NotNull BattleOutcome outcome,
        int elapsedSeconds,
        int killCount,
        @NotNull @Digits(integer = 20, fraction = 4) BigDecimal totalDamage,
        int reachedWave,
        @NotNull Instant clientEndedAt) {
    BattleCompletionCommand toCommand() {
        return new BattleCompletionCommand(
                outcome, elapsedSeconds, killCount, totalDamage,
                reachedWave, clientEndedAt);
    }
}
