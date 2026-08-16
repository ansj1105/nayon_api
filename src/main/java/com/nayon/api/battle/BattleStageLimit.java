package com.nayon.api.battle;

public record BattleStageLimit(
        String stageCode,
        int stageIndex,
        int maxWave,
        int maxKills,
        long maxDamage,
        long clearGold,
        long clearExp) {
}
