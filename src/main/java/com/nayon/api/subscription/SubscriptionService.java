package com.nayon.api.subscription;

import com.nayon.api.store.StoreAccountHasher;
import com.nayon.api.store.google.GooglePlayGatewayException;
import com.nayon.api.subscription.google.GooglePlaySubscription;
import com.nayon.api.subscription.google.GooglePlaySubscriptionGateway;
import com.nayon.api.time.ServerClock;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionService {

    private final SubscriptionRepository repository;
    private final GooglePlaySubscriptionGateway gateway;
    private final StoreAccountHasher accountHasher;
    private final ServerClock clock;

    @Autowired
    public SubscriptionService(
            SubscriptionRepository repository,
            GooglePlaySubscriptionGateway gateway,
            StoreAccountHasher accountHasher,
            ServerClock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.accountHasher = accountHasher;
        this.clock = clock;
    }

    SubscriptionService(
            SubscriptionRepository repository,
            GooglePlaySubscriptionGateway gateway,
            StoreAccountHasher accountHasher,
            Clock clock) {
        this(repository, gateway, accountHasher, new ServerClock(clock));
    }

    public SubscriptionCatalog catalog(UUID accountId) {
        return repository.catalog(accountId, accountHasher.hash(accountId));
    }

    public List<PlayerSubscription> findAll(UUID accountId) {
        return repository.findAll(accountId);
    }

    public SubscriptionVerificationResult verify(
            UUID accountId,
            UUID requestId,
            String productId,
            String purchaseToken) {
        validate(productId, purchaseToken);
        SubscriptionVerificationAttempt attempt = repository.begin(
                accountId, requestId,
                hash(productId + "\n" + purchaseToken),
                productId, purchaseToken, hash(purchaseToken));
        if (attempt.replay() && attempt.result() != null) {
            return attempt.result().asReplay();
        }

        GooglePlaySubscription subscription;
        try {
            subscription = gateway.get(purchaseToken);
        } catch (GooglePlayGatewayException exception) {
            repository.fail(requestId, exception.code(), !exception.retryable());
            throw new SubscriptionException(exception.code(), exception.getMessage());
        }
        if (!attempt.productId().equals(subscription.productId())) {
            repository.fail(requestId,
                    "GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_MISMATCH", true);
            throw new SubscriptionException(
                    "GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_MISMATCH",
                    "Verified subscription product does not match request.");
        }
        if (!constantTimeEquals(accountHasher.hash(accountId),
                subscription.obfuscatedAccountId())) {
            repository.fail(requestId,
                    "GOOGLE_PLAY_SUBSCRIPTION_ACCOUNT_MISMATCH", true);
            throw new SubscriptionException(
                    "GOOGLE_PLAY_SUBSCRIPTION_ACCOUNT_MISMATCH",
                    "Verified subscription belongs to another account.");
        }
        if (subscription.startedAt() == null || subscription.expiresAt() == null
                || !subscription.expiresAt().isAfter(subscription.startedAt())) {
            repository.fail(requestId, "GOOGLE_PLAY_INVALID_RESPONSE", false);
            throw new SubscriptionException(
                    "GOOGLE_PLAY_INVALID_RESPONSE",
                    "Verified subscription has invalid lifecycle times.");
        }
        SubscriptionVerificationResult completed = repository.complete(
                accountId, requestId, subscription, clock.now());
        return attempt.replay() ? completed.asReplay() : completed;
    }

    public PlayerSubscription reconcileByToken(String purchaseToken) {
        if (purchaseToken == null || purchaseToken.isBlank()
                || purchaseToken.length() > 4096) {
            throw new IllegalArgumentException("Invalid Google Play subscription token");
        }
        String tokenHash = hash(purchaseToken);
        SubscriptionTokenOwner owner = repository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new SubscriptionException(
                        "SUBSCRIPTION_TOKEN_NOT_FOUND",
                        "Subscription token is not registered."));
        GooglePlaySubscription subscription;
        try {
            subscription = gateway.get(purchaseToken);
        } catch (GooglePlayGatewayException exception) {
            throw new SubscriptionException(exception.code(), exception.getMessage());
        }
        if (!owner.productId().equals(subscription.productId())) {
            throw new SubscriptionException(
                    "GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_MISMATCH",
                    "Verified subscription product does not match registration.");
        }
        if (!constantTimeEquals(accountHasher.hash(owner.accountId()),
                subscription.obfuscatedAccountId())) {
            throw new SubscriptionException(
                    "GOOGLE_PLAY_SUBSCRIPTION_ACCOUNT_MISMATCH",
                    "Verified subscription belongs to another account.");
        }
        return repository.reconcile(owner, subscription, clock.now());
    }

    private void validate(String productId, String purchaseToken) {
        if (productId == null || productId.isBlank() || productId.length() > 200
                || purchaseToken == null || purchaseToken.isBlank()
                || purchaseToken.length() > 4096) {
            throw new IllegalArgumentException("Invalid Google Play subscription request");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
