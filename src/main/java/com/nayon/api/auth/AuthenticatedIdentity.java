package com.nayon.api.auth;

public record AuthenticatedIdentity(AuthProvider provider, String subject) {

    public AuthenticatedIdentity {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
    }
}
