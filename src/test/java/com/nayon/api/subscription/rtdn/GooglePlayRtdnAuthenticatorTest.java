package com.nayon.api.subscription.rtdn;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GooglePlayRtdnAuthenticatorTest {

    @Test
    void acceptsOnlyExactGoogleAudienceAndServiceAccount() {
        GooglePlayRtdnAuthenticator valid = authenticator(
                List.of("https://api.example/rtdn"),
                "rtdn@example.iam.gserviceaccount.com", true);
        assertThatCode(() -> valid.authenticate("Bearer signed-token"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> authenticator(
                List.of("https://other.example/rtdn"),
                "rtdn@example.iam.gserviceaccount.com", true)
                .authenticate("Bearer signed-token"))
                .isInstanceOfSatisfying(GooglePlayRtdnException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(
                                exception.code())
                                .isEqualTo("GOOGLE_PLAY_RTDN_UNAUTHORIZED"));
        assertThatThrownBy(() -> authenticator(
                List.of("https://api.example/rtdn"),
                "attacker@example.iam.gserviceaccount.com", true)
                .authenticate("Bearer signed-token"))
                .isInstanceOfSatisfying(GooglePlayRtdnException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(
                                exception.code())
                                .isEqualTo("GOOGLE_PLAY_RTDN_FORBIDDEN"));
    }

    @Test
    void rejectsMissingBearerAndUnverifiedEmail() {
        GooglePlayRtdnAuthenticator authenticator = authenticator(
                List.of("https://api.example/rtdn"),
                "rtdn@example.iam.gserviceaccount.com", false);

        assertThatThrownBy(() -> authenticator.authenticate(null))
                .isInstanceOf(GooglePlayRtdnException.class);
        assertThatThrownBy(() -> authenticator.authenticate("Bearer signed-token"))
                .isInstanceOfSatisfying(GooglePlayRtdnException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(
                                exception.code())
                                .isEqualTo("GOOGLE_PLAY_RTDN_FORBIDDEN"));
    }

    private GooglePlayRtdnAuthenticator authenticator(
            List<String> audience, String email, boolean emailVerified) {
        JwtDecoder decoder = token -> new Jwt(
                token, Instant.now().minusSeconds(1), Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"), Map.of(
                "iss", "https://accounts.google.com",
                "aud", audience,
                "email", email,
                "email_verified", emailVerified));
        return new GooglePlayRtdnAuthenticator(
                decoder, "https://api.example/rtdn",
                "rtdn@example.iam.gserviceaccount.com");
    }
}
