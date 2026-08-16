package com.nayon.api.battle;

public record BattleAnomaly(
        String ruleCode,
        String severity,
        String observedValue,
        String expectedValue) {
}
