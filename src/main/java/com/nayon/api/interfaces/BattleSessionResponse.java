package com.nayon.api.interfaces;

import com.nayon.api.battle.BattleSessionResult;

import java.time.Instant;
import java.util.UUID;

public record BattleSessionResponse(
        UUID battleId,
        String stageCode,
        String configVersion,
        Instant startedAt,
        Instant expiresAt,
        boolean replay) {
    static BattleSessionResponse from(BattleSessionResult result) {
        return new BattleSessionResponse(
                result.battleId(), result.stageCode(), result.configVersion(),
                result.startedAt(), result.expiresAt(), result.replay());
    }
}
