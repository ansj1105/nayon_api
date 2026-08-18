package com.nayon.api.weeklygift;

public class WeeklyGiftException extends RuntimeException {
    private final String code;

    public WeeklyGiftException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
