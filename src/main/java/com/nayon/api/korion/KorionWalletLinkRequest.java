package com.nayon.api.korion;

import java.time.Instant;
import java.util.UUID;

public record KorionWalletLinkRequest(
        UUID id,
        UUID accountId,
        String address,
        KorionWalletLinkStatus status,
        Instant expiresAt,
        String failureCode,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
}
