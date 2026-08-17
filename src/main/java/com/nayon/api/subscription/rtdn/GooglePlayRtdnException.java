package com.nayon.api.subscription.rtdn;

public class GooglePlayRtdnException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public GooglePlayRtdnException(String code, boolean retryable, String message) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public GooglePlayRtdnException(
            String code, boolean retryable, String message, Throwable cause) {
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
