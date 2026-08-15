package com.nayon.api.auth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public class CognitoTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final String clientId;

    public CognitoTokenValidator(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (!"access".equals(token.getClaimAsString("token_use"))) {
            return failure("invalid_token_use", "Only Cognito access tokens are accepted.");
        }
        if (!clientId.equals(token.getClaimAsString("client_id"))) {
            return failure("invalid_client_id", "The token was issued for another client.");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidatorResult failure(String code, String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(code, description, null));
    }
}
