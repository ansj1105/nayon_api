package com.nayon.api.store.google;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class HttpGooglePlayPurchaseGatewayTest {

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
    void parsesPurchasedProductV2AndSendsBearerToken() {
        server.createContext("/androidpublisher/v3/applications/com.korion.Nayon/"
                        + "purchases/productsv2/tokens/token-1",
                exchange -> {
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                            .isEqualTo("Bearer test-access-token");
                    respond(exchange, 200, """
                            {
                              "productLineItem":[{"productId":"nayon.diamond.100"}],
                              "purchaseStateContext":{"purchaseState":"PURCHASED"},
                              "orderId":"GPA.test-order",
                              "obfuscatedExternalAccountId":"abc123",
                              "purchaseCompletionTime":"2026-08-17T00:00:00Z",
                              "acknowledgementState":"ACKNOWLEDGEMENT_STATE_PENDING"
                            }
                            """);
                });
        HttpGooglePlayPurchaseGateway gateway = gateway();

        GooglePlayPurchase purchase = gateway.get("token-1");

        assertThat(purchase.productIds()).containsExactly("nayon.diamond.100");
        assertThat(purchase.state()).isEqualTo(GooglePlayPurchaseState.PURCHASED);
        assertThat(purchase.orderId()).isEqualTo("GPA.test-order");
        assertThat(purchase.obfuscatedAccountId()).isEqualTo("abc123");
        assertThat(purchase.purchaseTime()).isEqualTo(Instant.parse("2026-08-17T00:00:00Z"));
    }

    @Test
    void mapsPendingStateAndRejectsMalformedResponse() {
        server.createContext("/androidpublisher/v3/applications/com.korion.Nayon/"
                        + "purchases/productsv2/tokens/pending",
                exchange -> respond(exchange, 200, """
                        {"productLineItem":[{"productId":"nayon.diamond.100"}],
                         "purchaseStateContext":{"purchaseState":"PENDING"}}
                        """));
        server.createContext("/androidpublisher/v3/applications/com.korion.Nayon/"
                        + "purchases/productsv2/tokens/malformed",
                exchange -> respond(exchange, 200, "{}"));
        HttpGooglePlayPurchaseGateway gateway = gateway();

        assertThat(gateway.get("pending").state())
                .isEqualTo(GooglePlayPurchaseState.PENDING);
        assertThatThrownBy(() -> gateway.get("malformed"))
                .isInstanceOf(GooglePlayGatewayException.class)
                .extracting(exception -> ((GooglePlayGatewayException) exception).code())
                .isEqualTo("GOOGLE_PLAY_INVALID_RESPONSE");
    }

    @Test
    void mapsNotFoundRateLimitAndTransientServerFailure() {
        server.createContext("/androidpublisher/v3/applications/com.korion.Nayon/"
                        + "purchases/productsv2/tokens/missing",
                exchange -> respond(exchange, 404, "{}"));
        server.createContext("/androidpublisher/v3/applications/com.korion.Nayon/"
                        + "purchases/productsv2/tokens/limited",
                exchange -> respond(exchange, 429, "{}"));
        server.createContext("/androidpublisher/v3/applications/com.korion.Nayon/"
                        + "purchases/productsv2/tokens/failure",
                exchange -> respond(exchange, 500, "{}"));
        HttpGooglePlayPurchaseGateway gateway = gateway();

        assertCode(gateway, "missing", "GOOGLE_PLAY_PURCHASE_NOT_FOUND", false);
        assertCode(gateway, "limited", "GOOGLE_PLAY_RATE_LIMITED", true);
        assertCode(gateway, "failure", "GOOGLE_PLAY_UNAVAILABLE", true);
    }

    private HttpGooglePlayPurchaseGateway gateway() {
        return new HttpGooglePlayPurchaseGateway(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                new ObjectMapper(),
                () -> "test-access-token",
                baseUrl,
                "com.korion.Nayon",
                Duration.ofSeconds(2));
    }

    private void assertCode(
            HttpGooglePlayPurchaseGateway gateway,
            String token,
            String code,
            boolean retryable) {
        assertThatThrownBy(() -> gateway.get(token))
                .isInstanceOfSatisfying(GooglePlayGatewayException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(code);
                    assertThat(exception.retryable()).isEqualTo(retryable);
                });
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
