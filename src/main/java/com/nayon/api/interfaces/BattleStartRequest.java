package com.nayon.api.interfaces;

import com.nayon.api.battle.BattleStartCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BattleStartRequest(
        @NotBlank @Size(max = 80) String stageCode,
        @NotBlank @Size(max = 40) String clientBuild) {
    BattleStartCommand toCommand() {
        return new BattleStartCommand(stageCode, clientBuild);
    }
}
