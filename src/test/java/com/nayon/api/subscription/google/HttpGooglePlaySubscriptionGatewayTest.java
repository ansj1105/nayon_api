package com.nayon.api.subscription.google;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.store.google.GooglePlayGatewayException;
import com.nayon.api.subscription.SubscriptionState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpGooglePlaySubscriptionGatewayTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parsesIndependentActiveSubscriptionV2() {
        server.createContext(path("active"), exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer test-access-token");
            respond(exchange, 200, """
                    {
                      "startTime":"2026-08-18T00:00:00Z",
                      "subscriptionState":"SUBSCRIPTION_STATE_ACTIVE",
                      "acknowledgementState":"ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                      "externalAccountIdentifiers":{"obfuscatedExternalAccountId":"abc123"},
                      "linkedPurchaseToken":"old-token",
                      "latestOrderId":"GPA.test-order",
                      "lineItems":[{
                        "productId":"nayon.monthly.growth",
                        "expiryTime":"2026-09-18T00:00:00Z",
                        "autoRenewingPlan":{"autoRenewEnabled":true}
                      }]
                    }
                    """);
        });

        GooglePlaySubscription subscription = gateway().get("active");

        assertThat(subscription.productId()).isEqualTo("nayon.monthly.growth");
        assertThat(subscription.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(subscription.startedAt()).isEqualTo(
                Instant.parse("2026-08-18T00:00:00Z"));
        assertThat(subscription.expiresAt()).isEqualTo(
                Instant.parse("2026-09-18T00:00:00Z"));
        assertThat(subscription.autoRenewing()).isTrue();
        assertThat(subscription.acknowledged()).isTrue();
        assertThat(subscription.obfuscatedAccountId()).isEqualTo("abc123");
        assertThat(subscription.linkedPurchaseToken()).isEqualTo("old-token");
    }

    @Test
    void mapsCanceledGraceHoldPausedExpiredAndRevokedStates() {
        assertState("canceled", "SUBSCRIPTION_STATE_CANCELED", SubscriptionState.CANCELED);
        assertState("grace", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", SubscriptionState.GRACE_PERIOD);
        assertState("hold", "SUBSCRIPTION_STATE_ON_HOLD", SubscriptionState.ON_HOLD);
        assertState("paused", "SUBSCRIPTION_STATE_PAUSED", SubscriptionState.PAUSED);
        assertState("expired", "SUBSCRIPTION_STATE_EXPIRED", SubscriptionState.EXPIRED);
        assertState("revoked", "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED", SubscriptionState.REVOKED);
    }

    @Test
    void rejectsMultipleProductsAndMapsTransientFailures() {
        server.createContext(path("multiple"), exchange -> respond(exchange, 200, """
                {"startTime":"2026-08-18T00:00:00Z",
                 "subscriptionState":"SUBSCRIPTION_STATE_ACTIVE",
                 "acknowledgementState":"ACKNOWLEDGEMENT_STATE_PENDING",
                 "lineItems":[
                   {"productId":"a","expiryTime":"2026-09-18T00:00:00Z"},
                   {"productId":"b","expiryTime":"2026-09-18T00:00:00Z"}]}
                """));
        server.createContext(path("limited"), exchange -> respond(exchange, 429, "{}"));

        assertThatThrownBy(() -> gateway().get("multiple"))
                .isInstanceOfSatisfying(GooglePlayGatewayException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("GOOGLE_PLAY_INVALID_RESPONSE"));
        assertThatThrownBy(() -> gateway().get("limited"))
                .isInstanceOfSatisfying(GooglePlayGatewayException.class,
                        exception -> {
                            assertThat(exception.code()).isEqualTo("GOOGLE_PLAY_RATE_LIMITED");
                            assertThat(exception.retryable()).isTrue();
                        });
    }

    private void assertState(
            String token, String googleState, SubscriptionState expected) {
        server.createContext(path(token), exchange -> respond(exchange, 200, """
                {"startTime":"2026-08-18T00:00:00Z",
                 "subscriptionState":"%s",
                 "acknowledgementState":"ACKNOWLEDGEMENT_STATE_PENDING",
                 "lineItems":[{"productId":"nayon.monthly.growth",
                   "expiryTime":"2026-09-18T00:00:00Z"}]}
                """.formatted(googleState)));
        assertThat(gateway().get(token).state()).isEqualTo(expected);
    }

    private HttpGooglePlaySubscriptionGateway gateway() {
        return new HttpGooglePlaySubscriptionGateway(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                new ObjectMapper(),
                () -> "test-access-token", baseUrl,
                "com.korion.Nayon", Duration.ofSeconds(2));
    }

    private String path(String token) {
        return "/androidpublisher/v3/applications/com.korion.Nayon/"
                + "purchases/subscriptionsv2/tokens/" + token;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
