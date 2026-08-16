package com.nayon.api.battle.offline;

import com.nayon.api.battle.BattleOutcome;
import com.nayon.api.battle.BattleStageCatalog;
import com.nayon.api.battle.BattleStageLimit;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class OfflineBattleEvaluator {
    private final BattleStageCatalog.Configuration defaultRules;

    public OfflineBattleEvaluator(BattleStageCatalog catalog) {
        this.defaultRules = catalog.configuration();
    }

    public List<String> evaluate(
            OfflinePlayBudget budget,
            List<OfflineBattleRunCommand> runs,
            Instant now) {
        List<String> anomalies = new ArrayList<>();
        long elapsedBudget = Math.max(0, Duration.between(
                budget.openedAt(), now.isBefore(budget.expiresAt())
                        ? now : budget.expiresAt()).toSeconds());
        long available = Math.max(0, elapsedBudget - budget.consumedSeconds());
        long requested = runs.stream()
                .mapToLong(OfflineBattleRunCommand::elapsedSeconds).sum();
        if (now.isAfter(budget.expiresAt()))
            anomalies.add("OFFLINE_WINDOW_EXPIRED");
        if (requested > available)
            anomalies.add("OFFLINE_TIME_BUDGET_EXCEEDED");

        Set<java.util.UUID> runIds = new HashSet<>();
        BattleStageCatalog.Configuration rules = budget.rules() == null
                ? defaultRules : budget.rules();
        for (OfflineBattleRunCommand run : runs) {
            if (!runIds.add(run.runId()))
                addOnce(anomalies, "DUPLICATE_RUN_ID");
            BattleStageLimit stage = require(rules, run.stageCode());
            long maxKills = stage.maxKills()
                    + stage.maxKills() * rules
                            .killTolerancePercent() / 100L;
            if (run.outcome() == BattleOutcome.CLEAR
                    && run.elapsedSeconds() < rules
                            .minimumClearServerSeconds())
                addOnce(anomalies, "CLEAR_TOO_FAST");
            if (run.outcome() == BattleOutcome.CLEAR
                    && run.reachedWave() != stage.maxWave())
                addOnce(anomalies, "CLEAR_WAVE_INCOMPLETE");
            if (run.outcome() == BattleOutcome.CLEAR && run.killCount() == 0)
                addOnce(anomalies, "CLEAR_WITHOUT_KILLS");
            if (run.outcome() == BattleOutcome.CLEAR
                    && run.totalDamage().signum() == 0)
                addOnce(anomalies, "CLEAR_WITHOUT_DAMAGE");
            if (run.outcome() == BattleOutcome.CLEAR)
                addOnce(anomalies, "OFFLINE_CLEAR_REQUIRES_REVIEW");
            if (run.reachedWave() > stage.maxWave())
                addOnce(anomalies, "WAVE_ABOVE_MAX");
            if (run.killCount() > maxKills)
                addOnce(anomalies, "KILL_COUNT_ABOVE_MAX");
            if (run.totalDamage().compareTo(
                    java.math.BigDecimal.valueOf(stage.maxDamage())) > 0)
                addOnce(anomalies, "DAMAGE_ABOVE_MAX");
        }
        return List.copyOf(anomalies);
    }

    BattleStageLimit require(
            BattleStageCatalog.Configuration rules, String stageCode) {
        return rules.stages().stream()
                .filter(stage -> stage.stageCode().equals(stageCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown stage code"));
    }

    private void addOnce(List<String> anomalies, String code) {
        if (!anomalies.contains(code)) anomalies.add(code);
    }
}
