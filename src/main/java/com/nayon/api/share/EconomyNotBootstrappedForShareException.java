package com.nayon.api.share;

public class EconomyNotBootstrappedForShareException extends RuntimeException {
    public EconomyNotBootstrappedForShareException() {
        super("Player economy must be bootstrapped before claiming the share reward.");
    }
}
