package com.nayon.api.interfaces;

import com.nayon.api.battle.offline.OfflineBattleWindowResult;

import java.time.Instant;
import java.util.UUID;

public record OfflineBattleWindowResponse(
        UUID windowId,
        Instant openedAt,
        Instant expiresAt,
        boolean replay) {
    static OfflineBattleWindowResponse from(OfflineBattleWindowResult result) {
        return new OfflineBattleWindowResponse(
                result.windowId(), result.openedAt(), result.expiresAt(), result.replay());
    }
}
