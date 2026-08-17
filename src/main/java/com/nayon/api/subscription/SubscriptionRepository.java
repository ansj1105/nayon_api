package com.nayon.api.subscription;

import com.nayon.api.subscription.google.GooglePlaySubscription;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface SubscriptionRepository {
    SubscriptionCatalog catalog(UUID accountId, String obfuscatedAccountId);

    List<PlayerSubscription> findAll(UUID accountId);

    SubscriptionVerificationAttempt begin(
            UUID accountId,
            UUID requestId,
            String requestHash,
            String productId,
            String purchaseToken,
            String purchaseTokenHash);

    SubscriptionVerificationResult complete(
            UUID accountId,
            UUID requestId,
            GooglePlaySubscription subscription,
            Instant verifiedAt);

    void fail(UUID requestId, String code, boolean terminal);

    Optional<SubscriptionTokenOwner> findByTokenHash(String purchaseTokenHash);

    PlayerSubscription reconcile(
            SubscriptionTokenOwner owner,
            GooglePlaySubscription subscription,
            Instant verifiedAt);
}
