package com.nayon.api.subscription;

import com.nayon.api.store.StoreAccountHasher;
import com.nayon.api.subscription.google.GooglePlaySubscription;
import com.nayon.api.subscription.google.GooglePlaySubscriptionGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");

    @Test
    void verifiesOnlyTheMatchingIndependentPlan() {
        FakeRepository repository = new FakeRepository();
        SubscriptionService service = service(repository,
                new GooglePlaySubscription(
                        "nayon.monthly.growth", SubscriptionState.ACTIVE,
                        "GPA.order", expectedAccountHash(), NOW,
                        NOW.plusSeconds(2_678_400), true, true, null));

        SubscriptionVerificationResult result = service.verify(
                ACCOUNT_ID, UUID.randomUUID(),
                "nayon.monthly.growth", "opaque-token");

        assertThat(result.subscription().planCode())
                .isEqualTo(SubscriptionPlanCode.MONTHLY_GROWTH);
        assertThat(result.subscription().entitled(NOW)).isTrue();
        assertThat(repository.completed).hasSize(1);
    }

    @Test
    void rejectsProductAndAccountMismatchWithoutCompletingEntitlement() {
        FakeRepository repository = new FakeRepository();
        SubscriptionService productMismatch = service(repository,
                google("nayon.monthly.advanced", expectedAccountHash()));

        assertThatThrownBy(() -> productMismatch.verify(
                ACCOUNT_ID, UUID.randomUUID(),
                "nayon.monthly.growth", "opaque-token"))
                .isInstanceOfSatisfying(SubscriptionException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_MISMATCH"));

        SubscriptionService accountMismatch = service(repository,
                google("nayon.monthly.growth", "another-account"));
        assertThatThrownBy(() -> accountMismatch.verify(
                ACCOUNT_ID, UUID.randomUUID(),
                "nayon.monthly.growth", "opaque-token-2"))
                .isInstanceOfSatisfying(SubscriptionException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("GOOGLE_PLAY_SUBSCRIPTION_ACCOUNT_MISMATCH"));
        assertThat(repository.completed).isEmpty();
    }

    @Test
    void canceledRemainsEntitledUntilExpiryButHoldDoesNot() {
        PlayerSubscription canceled = PlayerSubscription.snapshot(
                UUID.randomUUID(), ACCOUNT_ID, SubscriptionPlanCode.MONTHLY_GROWTH,
                SubscriptionState.CANCELED, NOW.minusSeconds(10),
                NOW.plusSeconds(10), false, NOW, false);
        PlayerSubscription hold = PlayerSubscription.snapshot(
                UUID.randomUUID(), ACCOUNT_ID, SubscriptionPlanCode.MONTHLY_ADVANCED,
                SubscriptionState.ON_HOLD, NOW.minusSeconds(10),
                NOW.plusSeconds(10), false, NOW, false);

        assertThat(canceled.entitled(NOW)).isTrue();
        assertThat(canceled.entitled(NOW.plusSeconds(11))).isFalse();
        assertThat(hold.entitled(NOW)).isFalse();
    }

    private SubscriptionService service(
            FakeRepository repository, GooglePlaySubscription subscription) {
        return new SubscriptionService(repository, token -> subscription,
                new StoreAccountHasher("test-only-account-hash-key"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private GooglePlaySubscription google(String productId, String accountHash) {
        return new GooglePlaySubscription(
                productId, SubscriptionState.ACTIVE, "GPA.order", accountHash,
                NOW, NOW.plusSeconds(2_678_400), true, true, null);
    }

    private String expectedAccountHash() {
        return new StoreAccountHasher("test-only-account-hash-key").hash(ACCOUNT_ID);
    }

    private static final class FakeRepository implements SubscriptionRepository {
        private final List<GooglePlaySubscription> completed = new ArrayList<>();

        @Override
        public SubscriptionCatalog catalog(UUID accountId, String obfuscatedAccountId) {
            return new SubscriptionCatalog("GOOGLE_PLAY", obfuscatedAccountId, List.of());
        }

        @Override
        public List<PlayerSubscription> findAll(UUID accountId) {
            return List.of();
        }

        @Override
        public SubscriptionVerificationAttempt begin(
                UUID accountId, UUID requestId, String requestHash,
                String productId, String purchaseToken, String purchaseTokenHash) {
            return new SubscriptionVerificationAttempt(
                    UUID.randomUUID(), SubscriptionPlanCode.MONTHLY_GROWTH,
                    productId, false, null);
        }

        @Override
        public SubscriptionVerificationResult complete(
                UUID accountId, UUID requestId,
                GooglePlaySubscription subscription, Instant verifiedAt) {
            completed.add(subscription);
            return new SubscriptionVerificationResult(
                    PlayerSubscription.snapshot(
                            UUID.randomUUID(), accountId,
                            SubscriptionPlanCode.MONTHLY_GROWTH,
                            subscription.state(), subscription.startedAt(),
                            subscription.expiresAt(), subscription.autoRenewing(),
                            verifiedAt, false), null, false);
        }

        @Override
        public void fail(UUID requestId, String code, boolean terminal) {
        }

        @Override
        public Optional<SubscriptionTokenOwner> findByTokenHash(String purchaseTokenHash) {
            return Optional.empty();
        }

        @Override
        public PlayerSubscription reconcile(
                SubscriptionTokenOwner owner,
                GooglePlaySubscription subscription,
                Instant verifiedAt) {
            throw new UnsupportedOperationException();
        }
    }
}
