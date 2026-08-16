package com.nayon.api.battle.offline;

import com.nayon.api.battle.BattleOutcome;
import com.nayon.api.battle.BattleStageCatalog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineBattleEvaluatorTest {
    private final BattleStageCatalog catalog = new BattleStageCatalog(
            new com.fasterxml.jackson.databind.ObjectMapper());
    private final OfflineBattleEvaluator evaluator = new OfflineBattleEvaluator(catalog);

    @Test
    void reportedElapsedBeyondServerWindowIsHeld() {
        Instant opened = Instant.parse("2026-08-16T00:00:00Z");
        OfflinePlayBudget budget = new OfflinePlayBudget(
                UUID.randomUUID(), opened, opened.plusSeconds(3600), 0,
                catalog.configuration());
        OfflineBattleRunCommand run = new OfflineBattleRunCommand(
                UUID.randomUUID(), "STAGE_1", BattleOutcome.CLEAR,
                601, 100, BigDecimal.valueOf(1000), 16);

        List<String> anomalies = evaluator.evaluate(
                budget, List.of(run), opened.plusSeconds(600));

        assertThat(anomalies).contains("OFFLINE_TIME_BUDGET_EXCEEDED");
    }

    @Test
    void plausibleOfflineClearStillRequiresReviewBeforeMinting() {
        Instant opened = Instant.parse("2026-08-16T00:00:00Z");
        OfflinePlayBudget budget = new OfflinePlayBudget(
                UUID.randomUUID(), opened, opened.plusSeconds(3600), 0,
                catalog.configuration());
        OfflineBattleRunCommand run = new OfflineBattleRunCommand(
                UUID.randomUUID(), "STAGE_1", BattleOutcome.CLEAR,
                300, 100, BigDecimal.valueOf(1000), 16);

        assertThat(evaluator.evaluate(
                budget, List.of(run), opened.plusSeconds(600)))
                .containsExactly("OFFLINE_CLEAR_REQUIRES_REVIEW");
    }

    @Test
    void fabricatedClearWithoutCompletionEvidenceIsHeld() {
        Instant opened = Instant.parse("2026-08-16T00:00:00Z");
        OfflinePlayBudget budget = new OfflinePlayBudget(
                UUID.randomUUID(), opened, opened.plusSeconds(3600), 0,
                catalog.configuration());
        OfflineBattleRunCommand run = new OfflineBattleRunCommand(
                UUID.randomUUID(), "STAGE_1", BattleOutcome.CLEAR,
                5, 0, BigDecimal.ZERO, 0);

        assertThat(evaluator.evaluate(
                budget, List.of(run), opened.plusSeconds(600)))
                .contains("CLEAR_WAVE_INCOMPLETE", "CLEAR_WITHOUT_KILLS",
                        "CLEAR_WITHOUT_DAMAGE");
    }

    @Test
    void minimallyNonzeroFabricatedClearStillRequiresReview() {
        Instant opened = Instant.parse("2026-08-16T00:00:00Z");
        OfflinePlayBudget budget = new OfflinePlayBudget(
                UUID.randomUUID(), opened, opened.plusSeconds(3600), 0,
                catalog.configuration());
        OfflineBattleRunCommand run = new OfflineBattleRunCommand(
                UUID.randomUUID(), "STAGE_1", BattleOutcome.CLEAR,
                5, 1, new BigDecimal("0.0001"), 16);

        assertThat(evaluator.evaluate(
                budget, List.of(run), opened.plusSeconds(600)))
                .contains("OFFLINE_CLEAR_REQUIRES_REVIEW");
    }
}
