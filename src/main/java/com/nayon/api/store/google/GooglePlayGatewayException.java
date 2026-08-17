package com.nayon.api.store.google;

public class GooglePlayGatewayException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public GooglePlayGatewayException(String code, boolean retryable, String message) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public GooglePlayGatewayException(
            String code,
            boolean retryable,
            String message,
            Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
