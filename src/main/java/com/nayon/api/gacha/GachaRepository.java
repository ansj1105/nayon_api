package com.nayon.api.gacha;

import java.util.UUID;

public interface GachaRepository {
    GachaDrawResult draw(
            UUID accountId,
            UUID requestId,
            String requestHash,
            GachaSpec spec,
            GachaEngine engine);

    GachaHistoryPage history(UUID accountId, UUID before, int limit);
}
