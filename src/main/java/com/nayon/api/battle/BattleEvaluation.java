package com.nayon.api.battle;

import java.util.List;
import java.util.Map;

public record BattleEvaluation(
        List<BattleAnomaly> anomalies,
        Map<String, Object> details) {

    public BattleEvaluation {
        anomalies = List.copyOf(anomalies);
        details = Map.copyOf(details);
    }
}
