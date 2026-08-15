package com.nayon.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CognitoTokenValidatorTest {

    private final CognitoTokenValidator validator =
            new CognitoTokenValidator("nayon-unity-client");

    @Test
    void acceptsAccessTokenForConfiguredClient() {
        OAuth2TokenValidatorResult result = validator.validate(
                jwt("access", "nayon-unity-client"));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsIdToken() {
        OAuth2TokenValidatorResult result = validator.validate(
                jwt("id", "nayon-unity-client"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors().iterator().next().getErrorCode())
                .isEqualTo("invalid_token_use");
    }

    @Test
    void rejectsTokenForAnotherClient() {
        OAuth2TokenValidatorResult result = validator.validate(
                jwt("access", "another-client"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors().iterator().next().getErrorCode())
                .isEqualTo("invalid_client_id");
    }

    private Jwt jwt(String tokenUse, String clientId) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("subject-a")
                .claim("token_use", tokenUse)
                .claim("client_id", clientId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
