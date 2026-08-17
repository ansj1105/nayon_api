package com.nayon.api.levelreward;

public class LevelRewardException extends RuntimeException {

    private final String code;

    public LevelRewardException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
