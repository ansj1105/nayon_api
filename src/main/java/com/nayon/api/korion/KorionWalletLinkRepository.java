package com.nayon.api.korion;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface KorionWalletLinkRepository {
    void lockAccount(UUID accountId);
    void lockAddress(String address);
    Optional<KorionWalletLink> findLink(UUID accountId);
    Optional<KorionWalletLink> findLinkByAddress(String address);
    Optional<KorionWalletLinkRequest> findPending(UUID accountId);
    Optional<KorionWalletLinkRequest> findRequest(UUID accountId, UUID requestId);
    long countRequestsSince(UUID accountId, Instant since);
    KorionWalletLinkRequest create(UUID id, UUID accountId, String address, Instant expiresAt);
    KorionWalletLinkRequest finish(UUID accountId, UUID requestId, KorionWalletLinkStatus status,
                                   Instant expiresAt, String failureCode);
    KorionWalletLink link(UUID accountId, UUID requestId, String address);
    void unlink(UUID accountId);
}
