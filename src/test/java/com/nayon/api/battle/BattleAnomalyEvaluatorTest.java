package com.nayon.api.battle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BattleAnomalyEvaluatorTest {
    private final BattleStageCatalog catalog = new BattleStageCatalog(new ObjectMapper());
    private final BattleAnomalyEvaluator evaluator = new BattleAnomalyEvaluator();
    private final Instant started = Instant.parse("2026-08-16T00:00:00Z");
    private final BattleStageLimit stage = catalog.require("STAGE_1");

    @Test
    void validClearHasNoAnomaly() {
        BattleEvaluation result = evaluate(started.plusSeconds(300),
                command(BattleOutcome.CLEAR, 300, 100, 1000, 16,
                        started.plusSeconds(300)));

        assertThat(result.anomalies()).isEmpty();
        assertThat(result.details()).containsEntry(
                "configVersion", "unity-stage-2026-08-16");
    }

    @Test
    void everyDeterministicBoundaryProducesStableReason() {
        Instant completed = started.plusSeconds(3);
        BattleCompletionCommand command = command(
                BattleOutcome.CLEAR, -1, -1, -1,
                stage.maxWave() + 1, completed.plusSeconds(601));
        BattleEvaluation result = evaluator.evaluate(
                started, started.plusSeconds(2), completed,
                stage, catalog.configuration(), command);

        assertThat(result.anomalies()).extracting(BattleAnomaly::ruleCode)
                .containsExactly(
                        "SESSION_EXPIRED",
                        "ELAPSED_NEGATIVE",
                        "CLIENT_CLOCK_DRIFT",
                        "KILL_COUNT_NEGATIVE",
                        "DAMAGE_NEGATIVE",
                        "WAVE_ABOVE_MAX",
                        "CLEAR_TOO_FAST");
    }

    @Test
    void excessiveElapsedKillsAndDamageAreHeldReasons() {
        BattleCompletionCommand command = command(
                BattleOutcome.LOSS, 131, stage.maxKills() * 2,
                stage.maxDamage() + 1, 1, started.plusSeconds(100));
        BattleEvaluation result = evaluate(started.plusSeconds(100), command);

        assertThat(result.anomalies()).extracting(BattleAnomaly::ruleCode)
                .containsExactly(
                        "ELAPSED_EXCEEDS_SERVER",
                        "KILL_COUNT_ABOVE_MAX",
                        "DAMAGE_ABOVE_MAX");
    }

    private BattleEvaluation evaluate(
            Instant completed, BattleCompletionCommand command) {
        return evaluator.evaluate(
                started, started.plusSeconds(7200), completed,
                stage, catalog.configuration(), command);
    }

    private BattleCompletionCommand command(
            BattleOutcome outcome,
            int elapsed,
            int kills,
            long damage,
            int wave,
            Instant endedAt) {
        return new BattleCompletionCommand(
                outcome, elapsed, kills, BigDecimal.valueOf(damage), wave, endedAt);
    }
}
