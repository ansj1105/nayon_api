package com.nayon.api.korion;

public class KorionWalletLinkException extends RuntimeException {
    private final String code;

    public KorionWalletLinkException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
