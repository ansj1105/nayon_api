package com.nayon.api.auth;

public class InvalidIdentityClaimException extends RuntimeException {

    public InvalidIdentityClaimException(String message) {
        super(message);
    }
}
