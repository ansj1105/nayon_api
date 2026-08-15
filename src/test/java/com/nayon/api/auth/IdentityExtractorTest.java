package com.nayon.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityExtractorTest {

    private final IdentityExtractor extractor =
            new IdentityExtractor("nayon:provider", "sub");

    @Test
    void extractsGoogleIdentityFromTrustedClaims() {
        AuthenticatedIdentity identity = extractor.extract(jwt("GOOGLE", "google-subject-a"));

        assertThat(identity.provider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(identity.subject()).isEqualTo("google-subject-a");
    }

    @Test
    void extractsAppleIdentityFromTrustedClaims() {
        AuthenticatedIdentity identity = extractor.extract(jwt("APPLE", "apple-subject-a"));

        assertThat(identity.provider()).isEqualTo(AuthProvider.APPLE);
        assertThat(identity.subject()).isEqualTo("apple-subject-a");
    }

    @Test
    void rejectsMissingProviderInsteadOfFallingBackToEmailOrUsername() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-a")
                .claim("email", "same@example.com")
                .claim("username", "Google_subject-a")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        assertThatThrownBy(() -> extractor.extract(jwt))
                .isInstanceOf(InvalidIdentityClaimException.class)
                .hasMessageContaining("nayon:provider");
    }

    @Test
    void rejectsUnsupportedProvider() {
        assertThatThrownBy(() -> extractor.extract(jwt("FACEBOOK", "subject-a")))
                .isInstanceOf(InvalidIdentityClaimException.class)
                .hasMessageContaining("FACEBOOK");
    }

    private Jwt jwt(String provider, String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("nayon:provider", provider)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
