package com.nayon.api.subscription;

public class SubscriptionException extends RuntimeException {

    private final String code;

    public SubscriptionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
