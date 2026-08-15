package com.nayon.api.save;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("The idempotency key was already used for another request.");
    }
}
