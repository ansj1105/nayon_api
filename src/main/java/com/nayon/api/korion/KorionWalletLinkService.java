package com.nayon.api.korion;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class KorionWalletLinkService {
    private static final Pattern TRON_ADDRESS = Pattern.compile("^T[1-9A-HJ-NP-Za-km-z]{33}$");
    private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private final KorionWalletLinkRepository repository;
    private final KorionWalletGateway gateway;

    public KorionWalletLinkService(
            KorionWalletLinkRepository repository,
            KorionWalletGateway gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }

    @Transactional
    public KorionWalletLinkView get(UUID accountId) {
        repository.lockAccount(accountId);
        return repository.findLink(accountId)
                .map(KorionWalletLinkView::linked)
                .orElseGet(() -> repository.findPending(accountId)
                        .map(this::expireLocalIfNeeded)
                        .map(request -> KorionWalletLinkView.request(request, null))
                        .orElseGet(KorionWalletLinkView::empty));
    }

    @Transactional(noRollbackFor = KorionWalletLinkException.class)
    public KorionWalletLinkView create(UUID accountId, String rawAddress) {
        String address = normalizeAddress(rawAddress);
        repository.lockAccount(accountId);
        repository.findLink(accountId).ifPresent(link -> {
            throw new KorionWalletLinkException(
                    "KORION_WALLET_ALREADY_LINKED",
                    "A KORION wallet is already linked to this account.");
        });

        var pending = repository.findPending(accountId).map(this::expireLocalIfNeeded)
                .filter(request -> request.status() == KorionWalletLinkStatus.PENDING);
        if (pending.isPresent() && pending.get().address().equals(address)) {
            return submitToKorion(pending.get());
        }
        pending.ifPresent(value -> repository.finish(
                accountId, value.id(), KorionWalletLinkStatus.FAILED,
                value.expiresAt(), "SUPERSEDED"));

        Instant now = Instant.now();
        if (repository.countRequestsSince(accountId, now.minus(1, ChronoUnit.MINUTES)) >= 3) {
            throw new KorionWalletLinkException(
                    "KORION_WALLET_LINK_RATE_LIMITED",
                    "Too many KORION wallet-link requests.");
        }

        UUID requestId = UUID.randomUUID();
        Instant expiresAt = now.plus(10, ChronoUnit.MINUTES);
        KorionWalletLinkRequest local = repository.create(requestId, accountId, address, expiresAt);
        return submitToKorion(local);
    }

    private KorionWalletLinkView submitToKorion(KorionWalletLinkRequest local) {
        KorionWalletGateway.GatewayResult remote;
        try {
            remote = gateway.create(local.id(), local.address());
        } catch (KorionWalletLinkException exception) {
            if (isAmbiguousGatewayFailure(exception)) {
                repository.finish(local.accountId(), local.id(), KorionWalletLinkStatus.PENDING,
                        local.expiresAt(), exception.code());
            } else {
                repository.finish(local.accountId(), local.id(), KorionWalletLinkStatus.FAILED,
                        local.expiresAt(), exception.code());
            }
            throw exception;
        }
        validateRemote(local, remote);
        if (remote.status() == KorionWalletLinkStatus.APPROVED) {
            return linkApproved(local, remote.expiresAt());
        }
        KorionWalletLinkStatus status = remote.status() == KorionWalletLinkStatus.PENDING
                && !remote.expiresAt().isAfter(Instant.now())
                ? KorionWalletLinkStatus.EXPIRED : remote.status();
        KorionWalletLinkRequest updated = repository.finish(
                local.accountId(), local.id(), status, remote.expiresAt(), null);
        return KorionWalletLinkView.request(updated, remote.pushTargetAvailable());
    }

    @Transactional(noRollbackFor = KorionWalletLinkException.class)
    public KorionWalletLinkView reconcile(UUID accountId, UUID requestId) {
        repository.lockAccount(accountId);
        var linked = repository.findLink(accountId);
        if (linked.isPresent() && linked.get().verifiedRequestId().equals(requestId)) {
            return KorionWalletLinkView.linked(linked.get());
        }
        KorionWalletLinkRequest local = repository.findRequest(accountId, requestId)
                .orElseThrow(() -> new KorionWalletLinkException(
                        "KORION_WALLET_LINK_NOT_FOUND", "Wallet-link request not found."));
        if (local.status() != KorionWalletLinkStatus.PENDING) {
            return KorionWalletLinkView.request(local, null);
        }
        if (!isAmbiguousRequest(local)) {
            local = expireLocalIfNeeded(local);
        }
        if (local.status() != KorionWalletLinkStatus.PENDING) {
            return KorionWalletLinkView.request(local, null);
        }

        KorionWalletGateway.GatewayResult remote;
        try {
            remote = gateway.get(requestId);
        } catch (KorionWalletLinkException exception) {
            if ("KORION_WALLET_NOT_FOUND".equals(exception.code())) {
                local = repository.finish(accountId, requestId, KorionWalletLinkStatus.FAILED,
                        local.expiresAt(), exception.code());
                return KorionWalletLinkView.request(local, null);
            }
            throw exception;
        }
        validateRemote(local, remote);
        if (remote.status() != KorionWalletLinkStatus.APPROVED) {
            if (!remote.expiresAt().isAfter(Instant.now())) {
                local = repository.finish(accountId, requestId, KorionWalletLinkStatus.EXPIRED,
                        remote.expiresAt(), null);
                return KorionWalletLinkView.request(local, null);
            }
            local = repository.finish(accountId, requestId, remote.status(), remote.expiresAt(), null);
            return KorionWalletLinkView.request(local, null);
        }
        return linkApproved(local, remote.expiresAt());
    }

    @Transactional
    public void unlink(UUID accountId) {
        repository.lockAccount(accountId);
        repository.unlink(accountId);
    }

    private void validateRemote(
            KorionWalletLinkRequest local,
            KorionWalletGateway.GatewayResult remote) {
        if (!local.id().equals(remote.requestId()) || !local.address().equals(remote.address())) {
            repository.finish(local.accountId(), local.id(), KorionWalletLinkStatus.FAILED,
                    local.expiresAt(), "KORION_RESPONSE_MISMATCH");
            throw new KorionWalletLinkException(
                    "KORION_RESPONSE_MISMATCH",
                    "KORION wallet-link response did not match the request.");
        }
    }

    private KorionWalletLinkView linkApproved(
            KorionWalletLinkRequest request,
            Instant remoteExpiresAt) {
        repository.lockAddress(request.address());
        var owner = repository.findLinkByAddress(request.address());
        if (owner.isPresent() && !owner.get().accountId().equals(request.accountId())) {
            KorionWalletLinkRequest failed = repository.finish(
                    request.accountId(), request.id(), KorionWalletLinkStatus.FAILED,
                    remoteExpiresAt, "KORION_ADDRESS_ALREADY_LINKED");
            return KorionWalletLinkView.request(failed, null);
        }
        repository.finish(request.accountId(), request.id(), KorionWalletLinkStatus.APPROVED,
                remoteExpiresAt, null);
        return KorionWalletLinkView.linked(repository.link(
                request.accountId(), request.id(), request.address()));
    }

    private KorionWalletLinkRequest expireLocalIfNeeded(KorionWalletLinkRequest request) {
        if (request.status() == KorionWalletLinkStatus.PENDING
                && !isAmbiguousRequest(request)
                && !request.expiresAt().isAfter(Instant.now())) {
            return repository.finish(request.accountId(), request.id(),
                    KorionWalletLinkStatus.EXPIRED, request.expiresAt(), null);
        }
        return request;
    }

    private static boolean isAmbiguousRequest(KorionWalletLinkRequest request) {
        return "KORION_GATEWAY_FAILED".equals(request.failureCode())
                || "KORION_GATEWAY_INVALID_RESPONSE".equals(request.failureCode());
    }

    private static boolean isAmbiguousGatewayFailure(KorionWalletLinkException exception) {
        return "KORION_GATEWAY_FAILED".equals(exception.code())
                || "KORION_GATEWAY_INVALID_RESPONSE".equals(exception.code());
    }

    static String normalizeAddress(String rawAddress) {
        String address = rawAddress == null ? "" : rawAddress.trim();
        if (!TRON_ADDRESS.matcher(address).matches() || !validBase58Check(address)) {
            throw new IllegalArgumentException("Invalid TRON address.");
        }
        return address;
    }

    private static boolean validBase58Check(String address) {
        try {
            BigInteger value = BigInteger.ZERO;
            for (int index = 0; index < address.length(); index++) {
                int digit = BASE58.indexOf(address.charAt(index));
                if (digit < 0) return false;
                value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
            }
            byte[] raw = value.toByteArray();
            if (raw.length > 0 && raw[0] == 0) raw = Arrays.copyOfRange(raw, 1, raw.length);
            int leadingZeros = 0;
            while (leadingZeros < address.length() && address.charAt(leadingZeros) == '1') leadingZeros++;
            byte[] decoded = new byte[leadingZeros + raw.length];
            System.arraycopy(raw, 0, decoded, leadingZeros, raw.length);
            if (decoded.length != 25 || decoded[0] != 0x41) return false;
            byte[] payload = Arrays.copyOf(decoded, 21);
            byte[] checksum = Arrays.copyOfRange(decoded, 21, 25);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] first = sha.digest(payload);
            byte[] expected = Arrays.copyOf(sha.digest(first), 4);
            return MessageDigest.isEqual(checksum, expected);
        } catch (Exception exception) {
            return false;
        }
    }
}
