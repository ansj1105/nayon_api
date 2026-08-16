package com.nayon.api.korion;

import java.time.Instant;
import java.util.UUID;

public interface KorionWalletGateway {
    GatewayResult create(UUID requestId, String address);
    GatewayResult get(UUID requestId);

    record GatewayResult(
            UUID requestId,
            String address,
            KorionWalletLinkStatus status,
            Instant expiresAt,
            Boolean pushTargetAvailable) {
    }
}
