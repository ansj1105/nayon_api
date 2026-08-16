package com.nayon.api.battle;

import java.time.Instant;
import java.util.UUID;

public record BattleSessionResult(
        UUID battleId,
        String stageCode,
        String configVersion,
        Instant startedAt,
        Instant expiresAt,
        boolean replay) {

    public BattleSessionResult asReplay() {
        return new BattleSessionResult(
                battleId, stageCode, configVersion, startedAt, expiresAt, true);
    }
}
