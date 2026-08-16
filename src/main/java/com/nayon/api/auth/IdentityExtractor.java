package com.nayon.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class IdentityExtractor {

    private final String providerClaim;
    private final String subjectClaim;
    private final String configuredProvider;

    public IdentityExtractor(
            @Value("${nayon.auth.provider-claim:nayon:provider}") String providerClaim,
            @Value("${nayon.auth.subject-claim:sub}") String subjectClaim,
            @Value("${nayon.auth.provider:}") String configuredProvider) {
        this.providerClaim = providerClaim;
        this.subjectClaim = subjectClaim;
        this.configuredProvider = configuredProvider;
    }

    public AuthenticatedIdentity extract(Jwt jwt) {
        String providerValue = jwt.getClaimAsString(providerClaim);
        if (providerValue == null || providerValue.isBlank()) {
            providerValue = configuredProvider;
        }
        if (providerValue == null || providerValue.isBlank()) {
            throw new InvalidIdentityClaimException(
                    "Required identity claim is missing: " + providerClaim);
        }
        String subject = requiredClaim(jwt, subjectClaim);

        try {
            AuthProvider provider = AuthProvider.valueOf(providerValue.toUpperCase(Locale.ROOT));
            return new AuthenticatedIdentity(provider, subject);
        } catch (IllegalArgumentException exception) {
            throw new InvalidIdentityClaimException(
                    "Unsupported identity provider: " + providerValue);
        }
    }

    private String requiredClaim(Jwt jwt, String claimName) {
        String value = jwt.getClaimAsString(claimName);
        if (value == null || value.isBlank()) {
            throw new InvalidIdentityClaimException(
                    "Required identity claim is missing: " + claimName);
        }
        return value;
    }
}
