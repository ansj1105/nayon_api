package com.nayon.api.limitedbenefit.admob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Component
public class HttpAdMobPublicKeyProvider implements AdMobPublicKeyProvider {
    private static final Duration MAX_CACHE = Duration.ofHours(24);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI keysUri;
    private final Duration cacheDuration;
    private Map<Long, PublicKey> keys = Map.of();
    private Instant expiresAt = Instant.EPOCH;

    @Autowired
    public HttpAdMobPublicKeyProvider(
            ObjectMapper objectMapper,
            @Value("${nayon.limited-benefit.admob.keys-url}") URI keysUri,
            @Value("${nayon.limited-benefit.admob.keys-cache-duration:12h}")
            Duration cacheDuration) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper, keysUri, cacheDuration);
    }

    HttpAdMobPublicKeyProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI keysUri,
            Duration cacheDuration) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.keysUri = keysUri;
        this.cacheDuration = cacheDuration.compareTo(MAX_CACHE) > 0
                ? MAX_CACHE : cacheDuration;
    }

    @Override
    public synchronized PublicKey find(long keyId) {
        if (!Instant.now().isBefore(expiresAt)) {
            refresh();
        }
        return keys.get(keyId);
    }

    private void refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder(keysUri)
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new AdMobSsvVerificationException(
                        "Could not load AdMob verifier keys.");
            }
            JsonNode root = objectMapper.readTree(response.body());
            Map<Long, PublicKey> loaded = new HashMap<>();
            for (JsonNode node : root.path("keys")) {
                long id = node.path("keyId").asLong();
                String encoded = node.path("base64").asText();
                if (id > 0 && !encoded.isBlank()) {
                    loaded.put(id, KeyFactory.getInstance("EC").generatePublic(
                            new X509EncodedKeySpec(Base64.getDecoder().decode(encoded))));
                }
            }
            if (loaded.isEmpty()) {
                throw new AdMobSsvVerificationException(
                        "AdMob verifier key response was empty.");
            }
            keys = Map.copyOf(loaded);
            expiresAt = Instant.now().plus(cacheDuration);
        } catch (AdMobSsvVerificationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AdMobSsvVerificationException(
                    "Could not load AdMob verifier keys.", exception);
        }
    }
}
