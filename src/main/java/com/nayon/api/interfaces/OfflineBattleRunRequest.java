package com.nayon.api.interfaces;

import com.nayon.api.battle.BattleOutcome;
import com.nayon.api.battle.offline.OfflineBattleRunCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record OfflineBattleRunRequest(
        @NotNull UUID runId,
        @NotBlank @Size(max = 80) String stageCode,
        @NotNull BattleOutcome outcome,
        @Min(0) @Max(86400) int elapsedSeconds,
        @Min(0) int killCount,
        @NotNull @DecimalMin("0") @Digits(integer = 20, fraction = 4)
        BigDecimal totalDamage,
        @Min(0) int reachedWave) {
    OfflineBattleRunCommand toCommand() {
        return new OfflineBattleRunCommand(
                runId, stageCode, outcome, elapsedSeconds,
                killCount, totalDamage, reachedWave);
    }
}
