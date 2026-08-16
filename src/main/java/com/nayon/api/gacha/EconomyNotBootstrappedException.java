package com.nayon.api.gacha;

public class EconomyNotBootstrappedException extends RuntimeException {
    public EconomyNotBootstrappedException() {
        super("The account economy must be bootstrapped before drawing.");
    }
}
