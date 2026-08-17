package com.nayon.api.subscription.rtdn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GooglePlayRtdnAuthenticator {

    private final JwtDecoder decoder;
    private final String audience;
    private final String serviceAccountEmail;

    @Autowired
    public GooglePlayRtdnAuthenticator(
            @Value("${nayon.store.google-play.rtdn.jwk-set-uri:https://www.googleapis.com/oauth2/v3/certs}")
            String jwkSetUri,
            @Value("${nayon.store.google-play.rtdn.audience:}") String audience,
            @Value("${nayon.store.google-play.rtdn.service-account-email:}")
            String serviceAccountEmail) {
        this(NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build(),
                audience, serviceAccountEmail);
    }

    GooglePlayRtdnAuthenticator(
            JwtDecoder decoder, String audience, String serviceAccountEmail) {
        this.decoder = decoder;
        this.audience = audience;
        this.serviceAccountEmail = serviceAccountEmail;
    }

    public void authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            throw unauthorized("Google Pub/Sub bearer token is required.", null);
        }
        if (audience.isBlank() || serviceAccountEmail.isBlank()) {
            throw new GooglePlayRtdnException(
                    "GOOGLE_PLAY_RTDN_NOT_CONFIGURED", true,
                    "Google Play RTDN authentication is not configured.");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(authorization.substring(7));
        } catch (JwtException exception) {
            throw unauthorized("Google Pub/Sub bearer token is invalid.", exception);
        }
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        List<String> audiences = jwt.getAudience();
        if (!("https://accounts.google.com".equals(issuer)
                || "accounts.google.com".equals(issuer))
                || audiences == null || !audiences.contains(audience)) {
            throw unauthorized("Google Pub/Sub token issuer or audience is invalid.", null);
        }
        if (!serviceAccountEmail.equals(jwt.getClaimAsString("email"))
                || !Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw new GooglePlayRtdnException(
                    "GOOGLE_PLAY_RTDN_FORBIDDEN", false,
                    "Google Pub/Sub service account is not allowed.");
        }
    }

    private GooglePlayRtdnException unauthorized(String message, Throwable cause) {
        return new GooglePlayRtdnException(
                "GOOGLE_PLAY_RTDN_UNAUTHORIZED", false, message, cause);
    }
}
