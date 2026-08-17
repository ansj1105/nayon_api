package com.nayon.api.subscription.rtdn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.subscription.SubscriptionException;
import com.nayon.api.subscription.SubscriptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class GooglePlayRtdnService {

    private final GooglePlayRtdnAuthenticator authenticator;
    private final GooglePlayRtdnRepository repository;
    private final SubscriptionService subscriptions;
    private final ObjectMapper objectMapper;
    private final String packageName;

    public GooglePlayRtdnService(
            GooglePlayRtdnAuthenticator authenticator,
            GooglePlayRtdnRepository repository,
            SubscriptionService subscriptions,
            ObjectMapper objectMapper,
            @Value("${nayon.store.google-play.package-name:com.korion.Nayon}")
            String packageName) {
        this.authenticator = authenticator;
        this.repository = repository;
        this.subscriptions = subscriptions;
        this.objectMapper = objectMapper;
        this.packageName = packageName;
    }

    public void receive(String authorization, GooglePlayRtdnPushRequest request) {
        authenticator.authenticate(authorization);
        GooglePlayRtdnMessage message = parse(request);
        if (!packageName.equals(message.packageName())) {
            throw new GooglePlayRtdnException(
                    "GOOGLE_PLAY_RTDN_PACKAGE_MISMATCH", false,
                    "Google Play RTDN package does not match.");
        }
        if (!repository.begin(message, hash(message.purchaseToken()))) {
            return;
        }
        try {
            subscriptions.reconcileByToken(message.purchaseToken());
            repository.finish(message.messageId(), "PROCESSED", "UPDATED");
        } catch (SubscriptionException exception) {
            boolean retryable = isRetryable(exception.code());
            repository.finish(message.messageId(),
                    retryable ? "RETRYABLE_FAILED" : "REJECTED",
                    exception.code());
            throw new GooglePlayRtdnException(
                    exception.code(), retryable, exception.getMessage(), exception);
        }
    }

    private GooglePlayRtdnMessage parse(GooglePlayRtdnPushRequest request) {
        if (request == null || request.message() == null) {
            throw invalid("Google Play RTDN message is required.", null);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(request.message().data());
            if (decoded.length == 0 || decoded.length > 12_288) {
                throw invalid("Google Play RTDN payload size is invalid.", null);
            }
            JsonNode root = objectMapper.readTree(
                    new String(decoded, StandardCharsets.UTF_8));
            JsonNode notification = root.path("subscriptionNotification");
            String packageValue = root.path("packageName").asText("");
            String token = notification.path("purchaseToken").asText("");
            int type = notification.path("notificationType").asInt(0);
            if (request.message().messageId().isBlank()
                    || packageValue.isBlank() || token.isBlank()
                    || token.length() > 4096 || type < 1 || type > 22) {
                throw invalid("Google Play RTDN payload is invalid.", null);
            }
            return new GooglePlayRtdnMessage(
                    request.message().messageId(), packageValue, type, token);
        } catch (IllegalArgumentException | IOException exception) {
            throw invalid("Google Play RTDN payload is invalid.", exception);
        }
    }

    private boolean isRetryable(String code) {
        return "GOOGLE_PLAY_UNAVAILABLE".equals(code)
                || "GOOGLE_PLAY_RATE_LIMITED".equals(code)
                || "SUBSCRIPTION_TOKEN_NOT_FOUND".equals(code);
    }

    private GooglePlayRtdnException invalid(String message, Throwable cause) {
        return new GooglePlayRtdnException(
                "GOOGLE_PLAY_RTDN_INVALID", false, message, cause);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
