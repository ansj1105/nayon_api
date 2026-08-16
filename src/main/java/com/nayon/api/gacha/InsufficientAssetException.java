package com.nayon.api.gacha;

public class InsufficientAssetException extends RuntimeException {
    public InsufficientAssetException(String assetCode) {
        super("Insufficient " + assetCode + ".");
    }
}
