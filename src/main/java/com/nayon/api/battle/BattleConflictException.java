package com.nayon.api.battle;

public class BattleConflictException extends RuntimeException {
    public BattleConflictException() {
        super("The battle command conflicts with an existing command.");
    }
}
