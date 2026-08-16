package com.nayon.api.battle;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BattleAnomalyEvaluator {
    public BattleEvaluation evaluate(
            Instant startedAt,
            Instant expiresAt,
            Instant serverCompletedAt,
            BattleStageLimit stage,
            BattleStageCatalog.Configuration config,
            BattleCompletionCommand command) {
        long observedSeconds = Math.max(
                0, Duration.between(startedAt, serverCompletedAt).toSeconds());
        long maxKillsWithTolerance = stage.maxKills()
                + (stage.maxKills() * config.killTolerancePercent() / 100L);
        List<BattleAnomaly> anomalies = new ArrayList<>();

        if (serverCompletedAt.isAfter(expiresAt))
            add(anomalies, "SESSION_EXPIRED", "CRITICAL", serverCompletedAt, expiresAt);
        if (command.elapsedSeconds() < 0)
            add(anomalies, "ELAPSED_NEGATIVE", "CRITICAL", command.elapsedSeconds(), ">=0");
        if (command.elapsedSeconds() > observedSeconds + config.elapsedGraceSeconds())
            add(anomalies, "ELAPSED_EXCEEDS_SERVER", "CRITICAL", command.elapsedSeconds(),
                    "<=" + (observedSeconds + config.elapsedGraceSeconds()));
        long drift = Math.abs(Duration.between(
                command.clientEndedAt(), serverCompletedAt).toSeconds());
        if (drift > config.clockDriftSeconds())
            add(anomalies, "CLIENT_CLOCK_DRIFT", "WARNING", drift,
                    "<=" + config.clockDriftSeconds());
        if (command.killCount() < 0)
            add(anomalies, "KILL_COUNT_NEGATIVE", "CRITICAL", command.killCount(), ">=0");
        if (command.totalDamage().compareTo(BigDecimal.ZERO) < 0)
            add(anomalies, "DAMAGE_NEGATIVE", "CRITICAL", command.totalDamage(), ">=0");
        if (command.reachedWave() < 0)
            add(anomalies, "WAVE_NEGATIVE", "CRITICAL", command.reachedWave(), ">=0");
        if (command.reachedWave() > stage.maxWave())
            add(anomalies, "WAVE_ABOVE_MAX", "CRITICAL", command.reachedWave(),
                    "<=" + stage.maxWave());
        if (command.outcome() == BattleOutcome.CLEAR
                && observedSeconds < config.minimumClearServerSeconds())
            add(anomalies, "CLEAR_TOO_FAST", "CRITICAL", observedSeconds,
                    ">=" + config.minimumClearServerSeconds());
        if (command.killCount() > maxKillsWithTolerance)
            add(anomalies, "KILL_COUNT_ABOVE_MAX", "CRITICAL", command.killCount(),
                    "<=" + maxKillsWithTolerance);
        if (command.totalDamage().compareTo(BigDecimal.valueOf(stage.maxDamage())) > 0)
            add(anomalies, "DAMAGE_ABOVE_MAX", "CRITICAL", command.totalDamage(),
                    "<=" + stage.maxDamage());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("configVersion", config.version());
        details.put("serverObservedSeconds", observedSeconds);
        details.put("elapsedGraceSeconds", config.elapsedGraceSeconds());
        details.put("clockDriftSeconds", config.clockDriftSeconds());
        details.put("minimumClearServerSeconds", config.minimumClearServerSeconds());
        details.put("maxWave", stage.maxWave());
        details.put("maxKillsWithTolerance", maxKillsWithTolerance);
        details.put("maxDamage", stage.maxDamage());
        return new BattleEvaluation(anomalies, details);
    }

    private void add(
            List<BattleAnomaly> anomalies,
            String rule,
            String severity,
            Object observed,
            Object expected) {
        anomalies.add(new BattleAnomaly(
                rule, severity, String.valueOf(observed), String.valueOf(expected)));
    }
}
