package com.nayon.api.battle.offline;

import java.time.Instant;
import java.util.UUID;

public record OfflineBattleWindowResult(
        UUID windowId,
        Instant openedAt,
        Instant expiresAt,
        boolean replay) {
    public OfflineBattleWindowResult asReplay() {
        return new OfflineBattleWindowResult(windowId, openedAt, expiresAt, true);
    }
}
