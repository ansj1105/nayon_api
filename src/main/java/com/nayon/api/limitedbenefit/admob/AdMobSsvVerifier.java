package com.nayon.api.limitedbenefit.admob;

import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class AdMobSsvVerifier {
    private static final String SIGNATURE_MARKER = "&signature=";
    private static final String KEY_MARKER = "&key_id=";

    private final AdMobPublicKeyProvider keyProvider;

    public AdMobSsvVerifier(AdMobPublicKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    public AdMobSsvCallback verify(String rawQuery) {
        try {
            if (rawQuery == null || rawQuery.isBlank()) {
                throw new AdMobSsvVerificationException("AdMob callback query is missing.");
            }
            int signatureAt = rawQuery.indexOf(SIGNATURE_MARKER);
            int keyAt = rawQuery.indexOf(KEY_MARKER, signatureAt + SIGNATURE_MARKER.length());
            if (signatureAt <= 0 || keyAt <= signatureAt
                    || rawQuery.indexOf(SIGNATURE_MARKER, signatureAt + 1) >= 0
                    || rawQuery.indexOf(KEY_MARKER, keyAt + 1) >= 0
                    || rawQuery.indexOf('&', keyAt + KEY_MARKER.length()) >= 0) {
                throw new AdMobSsvVerificationException(
                        "AdMob signature and key_id must be the final ordered parameters.");
            }
            String signedContent = rawQuery.substring(0, signatureAt);
            String encodedSignature = rawQuery.substring(
                    signatureAt + SIGNATURE_MARKER.length(), keyAt);
            long keyId = Long.parseLong(rawQuery.substring(keyAt + KEY_MARKER.length()));
            PublicKey publicKey = keyProvider.find(keyId);
            if (publicKey == null) {
                throw new AdMobSsvVerificationException("Unknown AdMob verifier key.");
            }
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(signedContent.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getUrlDecoder().decode(encodedSignature))) {
                throw new AdMobSsvVerificationException("Invalid AdMob callback signature.");
            }

            Map<String, String> values = parseSignedParameters(signedContent);
            return new AdMobSsvCallback(
                    rawQuery,
                    keyId,
                    required(values, "ad_unit"),
                    UUID.fromString(required(values, "custom_data")),
                    Long.parseLong(required(values, "reward_amount")),
                    required(values, "reward_item"),
                    Instant.ofEpochMilli(Long.parseLong(required(values, "timestamp"))),
                    required(values, "transaction_id"),
                    UUID.fromString(required(values, "user_id")));
        } catch (AdMobSsvVerificationException exception) {
            throw exception;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new AdMobSsvVerificationException("Invalid AdMob callback.", exception);
        }
    }

    private Map<String, String> parseSignedParameters(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : content.split("&", -1)) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                throw new AdMobSsvVerificationException("Malformed AdMob callback parameter.");
            }
            String key = pair.substring(0, equals);
            String value = URLDecoder.decode(
                    pair.substring(equals + 1), StandardCharsets.UTF_8);
            if (values.putIfAbsent(key, value) != null) {
                throw new AdMobSsvVerificationException("Duplicate AdMob callback parameter.");
            }
        }
        return values;
    }

    private String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new AdMobSsvVerificationException(
                    "Missing AdMob callback parameter: " + key);
        }
        return value;
    }
}
