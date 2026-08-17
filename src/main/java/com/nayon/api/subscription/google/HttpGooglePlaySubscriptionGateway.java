package com.nayon.api.subscription.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.store.google.GooglePlayAccessTokenProvider;
import com.nayon.api.store.google.GooglePlayGatewayException;
import com.nayon.api.subscription.SubscriptionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

@Component
public class HttpGooglePlaySubscriptionGateway
        implements GooglePlaySubscriptionGateway {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GooglePlayAccessTokenProvider tokenProvider;
    private final String baseUrl;
    private final String packageName;
    private final Duration requestTimeout;

    @Autowired
    public HttpGooglePlaySubscriptionGateway(
            ObjectMapper objectMapper,
            GooglePlayAccessTokenProvider tokenProvider,
            @Value("${nayon.store.google-play.api-base-url:https://androidpublisher.googleapis.com}")
            String baseUrl,
            @Value("${nayon.store.google-play.package-name:com.korion.Nayon}")
            String packageName,
            @Value("${nayon.store.google-play.timeout:5s}") Duration requestTimeout) {
        this(HttpClient.newBuilder().connectTimeout(requestTimeout).build(),
                objectMapper, tokenProvider, baseUrl, packageName, requestTimeout);
    }

    HttpGooglePlaySubscriptionGateway(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            GooglePlayAccessTokenProvider tokenProvider,
            String baseUrl,
            String packageName,
            Duration requestTimeout) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.tokenProvider = tokenProvider;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.packageName = packageName;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public GooglePlaySubscription get(String purchaseToken) {
        String path = "/androidpublisher/v3/applications/" + segment(packageName)
                + "/purchases/subscriptionsv2/tokens/" + segment(purchaseToken);
        HttpResponse<String> response = send(path);
        requireSuccess(response.statusCode());
        return parse(response.body());
    }

    private HttpResponse<String> send(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + tokenProvider.accessToken())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new GooglePlayGatewayException(
                    "GOOGLE_PLAY_UNAVAILABLE", true,
                    "Google Play request failed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GooglePlayGatewayException(
                    "GOOGLE_PLAY_UNAVAILABLE", true,
                    "Google Play request was interrupted.", exception);
        }
    }

    private void requireSuccess(int status) {
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 404) {
            throw new GooglePlayGatewayException(
                    "GOOGLE_PLAY_SUBSCRIPTION_NOT_FOUND", false,
                    "Google Play subscription was not found.");
        }
        if (status == 429) {
            throw new GooglePlayGatewayException(
                    "GOOGLE_PLAY_RATE_LIMITED", true,
                    "Google Play rate limit was exceeded.");
        }
        throw new GooglePlayGatewayException(
                "GOOGLE_PLAY_UNAVAILABLE", status >= 500,
                "Google Play returned HTTP " + status + ".");
    }

    private GooglePlaySubscription parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode lineItems = root.path("lineItems");
            String stateValue = root.path("subscriptionState").asText("");
            String startValue = root.path("startTime").asText("");
            if (!lineItems.isArray() || lineItems.isEmpty()
                    || stateValue.isBlank() || startValue.isBlank()) {
                throw invalidResponse(null);
            }
            Set<String> products = new HashSet<>();
            Instant latestExpiry = null;
            boolean autoRenewing = false;
            for (JsonNode item : lineItems) {
                String productId = item.path("productId").asText("");
                String expiryValue = item.path("expiryTime").asText("");
                if (productId.isBlank() || expiryValue.isBlank()) {
                    throw invalidResponse(null);
                }
                products.add(productId);
                Instant expiry = Instant.parse(expiryValue);
                if (latestExpiry == null || expiry.isAfter(latestExpiry)) {
                    latestExpiry = expiry;
                }
                autoRenewing |= item.path("autoRenewingPlan")
                        .path("autoRenewEnabled").asBoolean(false);
            }
            if (products.size() != 1) {
                throw invalidResponse(null);
            }
            return new GooglePlaySubscription(
                    products.iterator().next(),
                    state(stateValue),
                    text(root, "latestOrderId"),
                    text(root.path("externalAccountIdentifiers"),
                            "obfuscatedExternalAccountId"),
                    Instant.parse(startValue),
                    latestExpiry,
                    autoRenewing,
                    "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED".equals(
                            root.path("acknowledgementState").asText()),
                    text(root, "linkedPurchaseToken"));
        } catch (GooglePlayGatewayException exception) {
            throw exception;
        } catch (IOException | DateTimeParseException exception) {
            throw invalidResponse(exception);
        }
    }

    private SubscriptionState state(String value) {
        return switch (value) {
            case "SUBSCRIPTION_STATE_PENDING" -> SubscriptionState.PENDING;
            case "SUBSCRIPTION_STATE_ACTIVE" -> SubscriptionState.ACTIVE;
            case "SUBSCRIPTION_STATE_CANCELED" -> SubscriptionState.CANCELED;
            case "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" -> SubscriptionState.GRACE_PERIOD;
            case "SUBSCRIPTION_STATE_ON_HOLD" -> SubscriptionState.ON_HOLD;
            case "SUBSCRIPTION_STATE_PAUSED" -> SubscriptionState.PAUSED;
            case "SUBSCRIPTION_STATE_EXPIRED" -> SubscriptionState.EXPIRED;
            case "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED" ->
                    SubscriptionState.REVOKED;
            default -> throw invalidResponse(null);
        };
    }

    private GooglePlayGatewayException invalidResponse(Throwable cause) {
        return new GooglePlayGatewayException(
                "GOOGLE_PLAY_INVALID_RESPONSE", false,
                "Google Play returned an invalid subscription response.", cause);
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
