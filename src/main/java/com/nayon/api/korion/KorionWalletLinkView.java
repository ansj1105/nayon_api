package com.nayon.api.korion;

import java.time.Instant;
import java.util.UUID;

public record KorionWalletLinkView(
        boolean linked,
        String address,
        UUID requestId,
        KorionWalletLinkStatus status,
        Instant expiresAt,
        Boolean pushTargetAvailable,
        String failureCode) {

    public static KorionWalletLinkView linked(KorionWalletLink link) {
        return new KorionWalletLinkView(true, link.address(), link.verifiedRequestId(),
                KorionWalletLinkStatus.APPROVED, null, null, null);
    }

    public static KorionWalletLinkView request(KorionWalletLinkRequest request, Boolean pushTargetAvailable) {
        return new KorionWalletLinkView(false, request.address(), request.id(), request.status(),
                request.expiresAt(), pushTargetAvailable, request.failureCode());
    }

    public static KorionWalletLinkView empty() {
        return new KorionWalletLinkView(false, null, null, null, null, null, null);
    }
}
