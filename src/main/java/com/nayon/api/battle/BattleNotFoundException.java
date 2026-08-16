package com.nayon.api.battle;

public class BattleNotFoundException extends RuntimeException {
    public BattleNotFoundException() {
        super("The battle does not exist for this account.");
    }
}
