package com.nayon.api.interfaces;

import com.nayon.api.korion.KorionWalletLinkStatus;
import com.nayon.api.korion.KorionWalletLinkView;

import java.time.Instant;
import java.util.UUID;

public record KorionWalletLinkResponse(
        boolean linked,
        String address,
        UUID requestId,
        KorionWalletLinkStatus status,
        Instant expiresAt,
        Boolean pushTargetAvailable,
        String failureCode) {

    public static KorionWalletLinkResponse from(KorionWalletLinkView view) {
        return new KorionWalletLinkResponse(
                view.linked(), view.address(), view.requestId(), view.status(),
                view.expiresAt(), view.pushTargetAvailable(), view.failureCode());
    }
}
