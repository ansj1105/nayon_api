package com.nayon.api.store.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;

@Component
public class HttpGooglePlayPurchaseGateway implements GooglePlayPurchaseGateway {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GooglePlayAccessTokenProvider tokenProvider;
    private final String baseUrl;
    private final String packageName;
    private final Duration requestTimeout;

    @Autowired
    public HttpGooglePlayPurchaseGateway(
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

    HttpGooglePlayPurchaseGateway(
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
    public GooglePlayPurchase get(String purchaseToken) {
        String path = "/androidpublisher/v3/applications/" + segment(packageName)
                + "/purchases/productsv2/tokens/" + segment(purchaseToken);
        HttpResponse<String> response = send("GET", path);
        requireSuccess(response.statusCode());
        return parse(response.body());
    }

    private HttpResponse<String> send(String method, String path) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + tokenProvider.accessToken())
                    .header("Accept", "application/json");
            HttpRequest request = "POST".equals(method)
                    ? builder.POST(HttpRequest.BodyPublishers.noBody()).build()
                    : builder.GET().build();
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
                    "GOOGLE_PLAY_PURCHASE_NOT_FOUND", false,
                    "Google Play purchase was not found.");
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

    private GooglePlayPurchase parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode lineItems = root.path("productLineItem");
            String stateValue = root.path("purchaseStateContext")
                    .path("purchaseState").asText("");
            if (!lineItems.isArray() || lineItems.isEmpty() || stateValue.isBlank()) {
                throw invalidResponse(null);
            }
            List<String> productIds = new ArrayList<>();
            for (JsonNode lineItem : lineItems) {
                String productId = lineItem.path("productId").asText("");
                if (productId.isBlank()) {
                    throw invalidResponse(null);
                }
                productIds.add(productId);
            }
            GooglePlayPurchaseState state = switch (stateValue) {
                case "PURCHASED", "PURCHASE_STATE_PURCHASED" ->
                        GooglePlayPurchaseState.PURCHASED;
                case "PENDING", "PURCHASE_STATE_PENDING" ->
                        GooglePlayPurchaseState.PENDING;
                case "CANCELLED", "PURCHASE_STATE_CANCELLED" ->
                        GooglePlayPurchaseState.CANCELLED;
                default -> throw invalidResponse(null);
            };
            String purchaseTimeValue = root.path("purchaseCompletionTime").asText(null);
            Instant purchaseTime = purchaseTimeValue == null
                    ? null : Instant.parse(purchaseTimeValue);
            return new GooglePlayPurchase(
                    List.copyOf(productIds),
                    state,
                    root.path("orderId").asText(null),
                    root.path("obfuscatedExternalAccountId").asText(null),
                    purchaseTime,
                    "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED".equals(
                            root.path("acknowledgementState").asText()));
        } catch (GooglePlayGatewayException exception) {
            throw exception;
        } catch (IOException | DateTimeParseException exception) {
            throw invalidResponse(exception);
        }
    }

    private GooglePlayGatewayException invalidResponse(Throwable cause) {
        return new GooglePlayGatewayException(
                "GOOGLE_PLAY_INVALID_RESPONSE", false,
                "Google Play returned an invalid purchase response.", cause);
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
