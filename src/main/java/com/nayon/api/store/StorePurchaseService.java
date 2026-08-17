package com.nayon.api.store;

import com.nayon.api.store.google.GooglePlayGatewayException;
import com.nayon.api.store.google.GooglePlayPurchase;
import com.nayon.api.store.google.GooglePlayPurchaseGateway;
import com.nayon.api.store.google.GooglePlayPurchaseState;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class StorePurchaseService {

    private final StorePurchaseRepository repository;
    private final GooglePlayPurchaseGateway gateway;
    private final StoreAccountHasher accountHasher;

    public StorePurchaseService(
            StorePurchaseRepository repository,
            GooglePlayPurchaseGateway gateway,
            StoreAccountHasher accountHasher) {
        this.repository = repository;
        this.gateway = gateway;
        this.accountHasher = accountHasher;
    }

    public StorePurchaseResult verify(
            UUID accountId,
            UUID requestId,
            StorePurchaseCommand command) {
        validate(command);
        StorePurchaseReceipt receipt = repository.begin(
                accountId,
                requestId,
                hash(command.productId() + "\n" + command.purchaseToken()),
                command.productId(),
                command.purchaseToken(),
                hash(command.purchaseToken()));
        boolean replay = receipt.replay();
        if (receipt.state() != StorePurchaseState.PENDING_VERIFICATION) {
            return new StorePurchaseResult(receipt, true);
        }
        String expectedAccount = accountHasher.hash(accountId);

        GooglePlayPurchase purchase;
        try {
            purchase = gateway.get(receipt.purchaseToken());
        } catch (GooglePlayGatewayException exception) {
            if ("GOOGLE_PLAY_PURCHASE_NOT_FOUND".equals(exception.code())) {
                repository.reject(receipt.id(), exception.code());
            } else {
                repository.markVerificationFailure(receipt.id(), exception.code());
            }
            throw new StorePurchaseException(exception.code(), exception.getMessage());
        }

        if (purchase.state() == GooglePlayPurchaseState.PENDING) {
            repository.markVerificationFailure(receipt.id(), "GOOGLE_PLAY_PURCHASE_PENDING");
            throw new StorePurchaseException(
                    "GOOGLE_PLAY_PURCHASE_PENDING", "Google Play purchase is pending.");
        }
        if (purchase.state() != GooglePlayPurchaseState.PURCHASED) {
            repository.reject(receipt.id(), "GOOGLE_PLAY_PURCHASE_CANCELLED");
            throw new StorePurchaseException(
                    "GOOGLE_PLAY_PURCHASE_CANCELLED", "Google Play purchase was cancelled.");
        }
        if (purchase.purchaseTime() == null) {
            repository.markVerificationFailure(
                    receipt.id(), "GOOGLE_PLAY_INVALID_RESPONSE");
            throw new StorePurchaseException(
                    "GOOGLE_PLAY_INVALID_RESPONSE",
                    "Verified purchase has no completion time.");
        }
        if (purchase.productIds().size() != 1
                || !receipt.productId().equals(purchase.productIds().getFirst())) {
            repository.reject(receipt.id(), "GOOGLE_PLAY_PRODUCT_MISMATCH");
            throw new StorePurchaseException(
                    "GOOGLE_PLAY_PRODUCT_MISMATCH", "Verified product does not match request.");
        }
        if (!constantTimeEquals(expectedAccount, purchase.obfuscatedAccountId())) {
            repository.reject(receipt.id(), "GOOGLE_PLAY_ACCOUNT_MISMATCH");
            throw new StorePurchaseException(
                    "GOOGLE_PLAY_ACCOUNT_MISMATCH", "Verified purchase belongs to another account.");
        }

        StorePurchaseReceipt granted;
        try {
            granted = repository.grant(receipt.id(), accountId, purchase);
        } catch (StorePurchaseException exception) {
            repository.markVerificationFailure(receipt.id(), exception.code());
            throw exception;
        }
        return new StorePurchaseResult(granted, replay || granted.replay());
    }

    private void validate(StorePurchaseCommand command) {
        if (command == null
                || command.productId() == null
                || command.productId().isBlank()
                || command.productId().length() > 200
                || command.purchaseToken() == null
                || command.purchaseToken().isBlank()
                || command.purchaseToken().length() > 4096) {
            throw new IllegalArgumentException("Invalid Google Play purchase command");
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
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
