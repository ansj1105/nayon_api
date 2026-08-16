package com.nayon.api.korion;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KorionWalletLinkServiceTest {
    private final MemoryRepository repository = new MemoryRepository();
    private final FakeGateway gateway = new FakeGateway();
    private final KorionWalletLinkService service = new KorionWalletLinkService(repository, gateway);

    @Test
    void linksOnlyAfterKorionReportsApproved() {
        UUID accountId = UUID.randomUUID();
        KorionWalletLinkView created = service.create(accountId, gateway.address);
        assertThat(created.linked()).isFalse();
        assertThat(created.status()).isEqualTo(KorionWalletLinkStatus.PENDING);

        gateway.status = KorionWalletLinkStatus.APPROVED;
        KorionWalletLinkView approved = service.reconcile(accountId, created.requestId());

        assertThat(approved.linked()).isTrue();
        assertThat(repository.findLink(accountId)).isPresent();
    }

    @Test
    void unlinkPreventsDelayedApprovalFromRestoringLink() {
        UUID accountId = UUID.randomUUID();
        KorionWalletLinkView created = service.create(accountId, gateway.address);
        service.unlink(accountId);
        gateway.status = KorionWalletLinkStatus.APPROVED;

        KorionWalletLinkView result = service.reconcile(accountId, created.requestId());

        assertThat(result.linked()).isFalse();
        assertThat(result.status()).isEqualTo(KorionWalletLinkStatus.FAILED);
    }

    @Test
    void expiredPendingRequestDoesNotBlockANewRequest() {
        UUID accountId = UUID.randomUUID();
        KorionWalletLinkRequest expired = repository.create(
                UUID.randomUUID(), accountId, gateway.address, Instant.now().minusSeconds(1));

        KorionWalletLinkView created = service.create(accountId, gateway.address);

        assertThat(repository.findRequest(accountId, expired.id()).orElseThrow().status())
                .isEqualTo(KorionWalletLinkStatus.EXPIRED);
        assertThat(created.requestId()).isNotEqualTo(expired.id());
        assertThat(created.status()).isEqualTo(KorionWalletLinkStatus.PENDING);
    }

    @Test
    void addressOwnedByAnotherAccountBecomesTerminalFailure() {
        UUID ownerAccount = UUID.randomUUID();
        UUID requesterAccount = UUID.randomUUID();
        repository.links.put(ownerAccount, new KorionWalletLink(
                ownerAccount, gateway.address, UUID.randomUUID(), Instant.now()));
        KorionWalletLinkView created = service.create(requesterAccount, gateway.address);
        gateway.status = KorionWalletLinkStatus.APPROVED;

        KorionWalletLinkView result = service.reconcile(requesterAccount, created.requestId());

        assertThat(result.linked()).isFalse();
        assertThat(result.status()).isEqualTo(KorionWalletLinkStatus.FAILED);
        assertThat(result.failureCode()).isEqualTo("KORION_ADDRESS_ALREADY_LINKED");
    }

    @Test
    void ambiguousCreateFailureKeepsTheSameRequestForRetry() {
        UUID accountId = UUID.randomUUID();
        gateway.createFailure = new KorionWalletLinkException(
                "KORION_GATEWAY_FAILED", "timeout");

        assertThatThrownBy(() -> service.create(accountId, gateway.address))
                .isInstanceOf(KorionWalletLinkException.class);
        KorionWalletLinkRequest pending = repository.findPending(accountId).orElseThrow();
        assertThat(pending.failureCode()).isEqualTo("KORION_GATEWAY_FAILED");

        gateway.createFailure = null;
        KorionWalletLinkView retried = service.create(accountId, gateway.address);

        assertThat(retried.requestId()).isEqualTo(pending.id());
        assertThat(repository.requests).hasSize(1);
    }

    @Test
    void ambiguousExpiredLocalRequestStillReconcilesRemoteApproval() {
        UUID accountId = UUID.randomUUID();
        KorionWalletLinkRequest pending = repository.create(
                UUID.randomUUID(), accountId, gateway.address, Instant.now().minusSeconds(1));
        repository.finish(accountId, pending.id(), KorionWalletLinkStatus.PENDING,
                pending.expiresAt(), "KORION_GATEWAY_FAILED");
        gateway.status = KorionWalletLinkStatus.APPROVED;

        KorionWalletLinkView result = service.reconcile(accountId, pending.id());

        assertThat(result.linked()).isTrue();
        assertThat(repository.findLink(accountId)).isPresent();
    }

    private static final class FakeGateway implements KorionWalletGateway {
        private final String address = "TJRabPrwbZy45sbavfcjinPJC18kjpRTv8";
        private KorionWalletLinkStatus status = KorionWalletLinkStatus.PENDING;
        private KorionWalletLinkException createFailure;

        @Override
        public GatewayResult create(UUID requestId, String requestedAddress) {
            if (createFailure != null) throw createFailure;
            return new GatewayResult(requestId, requestedAddress, status,
                    Instant.now().plusSeconds(600), true);
        }

        @Override
        public GatewayResult get(UUID requestId) {
            return new GatewayResult(requestId, address, status, Instant.now().plusSeconds(600), null);
        }
    }

    private static final class MemoryRepository implements KorionWalletLinkRepository {
        private final Map<UUID, KorionWalletLinkRequest> requests = new HashMap<>();
        private final Map<UUID, KorionWalletLink> links = new HashMap<>();

        public void lockAccount(UUID accountId) { }
        public void lockAddress(String address) { }
        public Optional<KorionWalletLink> findLink(UUID accountId) { return Optional.ofNullable(links.get(accountId)); }
        public Optional<KorionWalletLink> findLinkByAddress(String address) {
            return links.values().stream().filter(value -> value.address().equals(address)).findFirst();
        }
        public Optional<KorionWalletLinkRequest> findPending(UUID accountId) {
            return requests.values().stream().filter(value -> value.accountId().equals(accountId)
                    && value.status() == KorionWalletLinkStatus.PENDING).findFirst();
        }
        public Optional<KorionWalletLinkRequest> findRequest(UUID accountId, UUID requestId) {
            return Optional.ofNullable(requests.get(requestId)).filter(value -> value.accountId().equals(accountId));
        }
        public long countRequestsSince(UUID accountId, Instant since) { return 0; }
        public KorionWalletLinkRequest create(UUID id, UUID accountId, String address, Instant expiresAt) {
            KorionWalletLinkRequest value = new KorionWalletLinkRequest(id, accountId, address,
                    KorionWalletLinkStatus.PENDING, expiresAt, null, Instant.now(), Instant.now(), null);
            requests.put(id, value);
            return value;
        }
        public KorionWalletLinkRequest finish(UUID accountId, UUID id, KorionWalletLinkStatus status,
                                              Instant expiresAt, String failureCode) {
            KorionWalletLinkRequest old = requests.get(id);
            KorionWalletLinkRequest value = new KorionWalletLinkRequest(id, accountId, old.address(), status,
                    expiresAt, failureCode, old.createdAt(), Instant.now(), Instant.now());
            requests.put(id, value);
            return value;
        }
        public KorionWalletLink link(UUID accountId, UUID requestId, String address) {
            KorionWalletLink value = new KorionWalletLink(accountId, address, requestId, Instant.now());
            links.put(accountId, value);
            return value;
        }
        public void unlink(UUID accountId) {
            links.remove(accountId);
            findPending(accountId).ifPresent(value -> finish(accountId, value.id(),
                    KorionWalletLinkStatus.FAILED, value.expiresAt(), "UNLINKED"));
        }
    }
}
