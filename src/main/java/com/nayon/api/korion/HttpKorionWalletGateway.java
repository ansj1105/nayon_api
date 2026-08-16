package com.nayon.api.korion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class HttpKorionWalletGateway implements KorionWalletGateway {
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";
    private final RestClient client;
    private final String internalApiKey;

    public HttpKorionWalletGateway(
            @Value("${nayon.korion-wallet-link.base-url:}") String baseUrl,
            @Value("${nayon.korion-wallet-link.internal-api-key:}") String internalApiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3_000);
        requestFactory.setReadTimeout(5_000);
        this.client = baseUrl == null || baseUrl.isBlank()
                ? null
                : RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalApiKey = internalApiKey;
    }

    @Override
    public GatewayResult create(UUID requestId, String address) {
        requireConfigured();
        try {
            GatewayResponse response = client.post()
                    .uri("/api/v1/internal/nayon/wallet-link-requests")
                    .header(INTERNAL_KEY_HEADER, internalApiKey)
                    .body(Map.of(
                            "requestId", requestId.toString(),
                            "address", address))
                    .retrieve()
                    .body(GatewayResponse.class);
            return convert(response);
        } catch (RestClientResponseException exception) {
            throw map(exception);
        } catch (RestClientException exception) {
            throw transportFailure();
        }
    }

    @Override
    public GatewayResult get(UUID requestId) {
        requireConfigured();
        try {
            GatewayResponse response = client.get()
                    .uri("/api/v1/internal/nayon/wallet-link-requests/{requestId}", requestId)
                    .header(INTERNAL_KEY_HEADER, internalApiKey)
                    .retrieve()
                    .body(GatewayResponse.class);
            return convert(response);
        } catch (RestClientResponseException exception) {
            throw map(exception);
        } catch (RestClientException exception) {
            throw transportFailure();
        }
    }

    private void requireConfigured() {
        if (client == null || internalApiKey == null || internalApiKey.isBlank()) {
            throw new KorionWalletLinkException(
                    "KORION_GATEWAY_NOT_CONFIGURED",
                    "KORION wallet linking is not configured.");
        }
    }

    private GatewayResult convert(GatewayResponse response) {
        if (response == null || response.requestId() == null || response.address() == null
                || response.status() == null || response.expiresAt() == null) {
            throw new KorionWalletLinkException(
                    "KORION_GATEWAY_INVALID_RESPONSE",
                    "KORION returned an invalid wallet-link response.");
        }
        return new GatewayResult(response.requestId(), response.address(), response.status(),
                response.expiresAt(), response.pushTargetAvailable());
    }

    private KorionWalletLinkException map(RestClientResponseException exception) {
        String code = switch (exception.getStatusCode().value()) {
            case 404 -> "KORION_WALLET_NOT_FOUND";
            case 409 -> "KORION_WALLET_CONFLICT";
            case 429 -> "KORION_RATE_LIMITED";
            default -> "KORION_GATEWAY_FAILED";
        };
        return new KorionWalletLinkException(code, "KORION wallet verification request failed.");
    }

    private KorionWalletLinkException transportFailure() {
        return new KorionWalletLinkException(
                "KORION_GATEWAY_FAILED",
                "KORION wallet verification service is temporarily unavailable.");
    }

    private record GatewayResponse(
            UUID requestId,
            String address,
            KorionWalletLinkStatus status,
            Instant expiresAt,
            Boolean pushTargetAvailable) {
    }
}
