package com.nayon.api.gacha;

public class GachaConflictException extends RuntimeException {
    public GachaConflictException() {
        super("The idempotency key was reused for another gacha command.");
    }
}
