package com.nayon.api.economy;

public class EconomyBootstrapConflictException extends RuntimeException {

    public EconomyBootstrapConflictException() {
        super("The account economy has already been bootstrapped or the idempotency key was reused.");
    }
}
