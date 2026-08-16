package com.nayon.api.battle;

public class BattleEconomyNotBootstrappedException extends RuntimeException {
    public BattleEconomyNotBootstrappedException() {
        super("The account economy must be bootstrapped before starting a battle.");
    }
}
