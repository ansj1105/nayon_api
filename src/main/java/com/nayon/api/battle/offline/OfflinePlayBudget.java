package com.nayon.api.battle.offline;

import com.nayon.api.battle.BattleStageCatalog;

import java.time.Instant;
import java.util.UUID;

public record OfflinePlayBudget(
        UUID windowId,
        Instant openedAt,
        Instant expiresAt,
        long consumedSeconds,
        BattleStageCatalog.Configuration rules) {
}
