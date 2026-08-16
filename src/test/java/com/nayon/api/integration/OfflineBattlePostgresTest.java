package com.nayon.api.integration;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.battle.BattleOutcome;
import com.nayon.api.battle.BattleRewardState;
import com.nayon.api.battle.BattleCompletionCommand;
import com.nayon.api.battle.BattleService;
import com.nayon.api.battle.BattleStartCommand;
import com.nayon.api.battle.offline.OfflineBattleConflictException;
import com.nayon.api.battle.offline.OfflineBattleRunCommand;
import com.nayon.api.battle.offline.OfflineBattleService;
import com.nayon.api.battle.offline.OfflineBattleSubmissionCommand;
import com.nayon.api.battle.offline.OfflineBattleSubmissionResult;
import com.nayon.api.battle.offline.OfflineBattleWindowResult;
import com.nayon.api.economy.EconomyBootstrapCommand;
import com.nayon.api.economy.EconomyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "management.health.db.enabled=false")
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class OfflineBattlePostgresTest {
    @Autowired AccountService accountService;
    @Autowired EconomyService economyService;
    @Autowired OfflineBattleService service;
    @Autowired BattleService battleService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table offline_battle_decisions, offline_battle_runs, "
                + "offline_battle_submissions, offline_play_window_requests, "
                + "offline_play_budgets, battle_rewards, battle_anomalies, "
                + "battle_completions, battle_sessions, player_progression, "
                + "gacha_draw_results, gacha_draws, gacha_pity_states, "
                + "economy_bootstraps, economy_ledger, player_equipment, "
                + "player_items, player_wallets, save_imports, player_save_states, "
                + "auth_identities, player_accounts");
    }

    @Test
    void serverWindowAndSubmissionRetriesReplayExactly() {
        PlayerAccount account = bootstrapped("offline-replay");
        UUID syncKey = UUID.randomUUID();
        OfflineBattleWindowResult window = service.sync(account.id(), syncKey);
        OfflineBattleWindowResult windowReplay = service.sync(account.id(), syncKey);
        assertThat(windowReplay.windowId()).isEqualTo(window.windowId());
        assertThat(windowReplay.replay()).isTrue();

        makeElapsedAvailable(account.id());
        UUID requestId = UUID.randomUUID();
        OfflineBattleSubmissionCommand command = submission(window.windowId(), UUID.randomUUID());
        OfflineBattleSubmissionResult first = service.submit(account.id(), requestId, command);
        OfflineBattleSubmissionResult replay = service.submit(account.id(), requestId, command);

        assertThat(first.rewardState()).isEqualTo(BattleRewardState.HELD);
        assertThat(first.anomalyReasons()).contains("OFFLINE_CLEAR_REQUIRES_REVIEW");
        assertThat(replay.submissionId()).isEqualTo(first.submissionId());
        assertThat(replay.replay()).isTrue();
        assertThat(first.economy().currencies()).containsEntry("GOLD", 0L);
        assertThat(jdbc.queryForObject(
                "select count(*) from economy_ledger where reference_id = ?",
                Long.class, first.submissionId())).isZero();
    }

    @Test
    void delayedRetryOfOlderSyncReturnsCurrentUsableWindow() {
        PlayerAccount account = bootstrapped("offline-delayed-sync");
        UUID firstKey = UUID.randomUUID();
        OfflineBattleWindowResult first = service.sync(account.id(), firstKey);
        OfflineBattleWindowResult current = service.sync(
                account.id(), UUID.randomUUID());

        OfflineBattleWindowResult delayedRetry = service.sync(account.id(), firstKey);

        assertThat(current.windowId()).isNotEqualTo(first.windowId());
        assertThat(delayedRetry.windowId()).isEqualTo(current.windowId());
        assertThat(delayedRetry.replay()).isTrue();
    }

    @Test
    void elapsedBeyondServerObservedTimeIsHeldWithoutMinting() {
        PlayerAccount account = bootstrapped("offline-held");
        OfflineBattleWindowResult window = service.sync(account.id(), UUID.randomUUID());
        jdbc.update("update offline_play_budgets set opened_at = now() - interval '60 seconds' "
                + "where account_id = ?", account.id());

        OfflineBattleSubmissionResult result = service.submit(
                account.id(), UUID.randomUUID(),
                submission(window.windowId(), UUID.randomUUID()));

        assertThat(result.rewardState()).isEqualTo(BattleRewardState.HELD);
        assertThat(result.anomalyReasons()).contains("OFFLINE_TIME_BUDGET_EXCEEDED");
        assertThat(result.economy().currencies()).containsEntry("GOLD", 0L);
    }

    @Test
    void sameRunCannotBeSubmittedUnderAnotherRequest() {
        PlayerAccount account = bootstrapped("offline-duplicate");
        OfflineBattleWindowResult window = service.sync(account.id(), UUID.randomUUID());
        makeElapsedAvailable(account.id());
        UUID runId = UUID.randomUUID();
        service.submit(account.id(), UUID.randomUUID(), submission(window.windowId(), runId));

        assertThatThrownBy(() -> service.submit(
                account.id(), UUID.randomUUID(), submission(window.windowId(), runId)))
                .isInstanceOf(OfflineBattleConflictException.class);
    }

    @Test
    void lockedStageIsHeldWithoutMinting() {
        PlayerAccount account = bootstrapped("offline-locked-stage");
        OfflineBattleWindowResult window = service.sync(account.id(), UUID.randomUUID());
        makeElapsedAvailable(account.id());

        OfflineBattleSubmissionResult result = service.submit(
                account.id(), UUID.randomUUID(),
                new OfflineBattleSubmissionCommand(window.windowId(), List.of(
                        new OfflineBattleRunCommand(
                                UUID.randomUUID(), "STAGE_10", BattleOutcome.CLEAR,
                                300, 100, BigDecimal.valueOf(1000), 16))));

        assertThat(result.rewardState()).isEqualTo(BattleRewardState.HELD);
        assertThat(result.anomalyReasons()).contains("STAGE_NOT_UNLOCKED");
        assertThat(result.economy().currencies()).containsEntry("GOLD", 0L);
    }

    @Test
    void concurrentSubmissionsCannotSpendTheSameElapsedBudgetTwice()
            throws Exception {
        PlayerAccount account = bootstrapped("offline-concurrent");
        OfflineBattleWindowResult window = service.sync(
                account.id(), UUID.randomUUID());
        makeElapsedAvailable(account.id());
        Callable<OfflineBattleSubmissionResult> first = () -> service.submit(
                account.id(), UUID.randomUUID(),
                submissionWithElapsed(window.windowId(), UUID.randomUUID(), 400));
        Callable<OfflineBattleSubmissionResult> second = () -> service.submit(
                account.id(), UUID.randomUUID(),
                submissionWithElapsed(window.windowId(), UUID.randomUUID(), 400));

        List<OfflineBattleSubmissionResult> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            results = executor.invokeAll(List.of(first, second)).stream()
                    .map(future -> {
                        try { return future.get(); }
                        catch (Exception exception) { throw new AssertionError(exception); }
                    }).toList();
        }

        assertThat(results).extracting(OfflineBattleSubmissionResult::rewardState)
                .containsOnly(BattleRewardState.HELD);
        assertThat(jdbc.queryForObject(
                "select balance from player_wallets where account_id = ? "
                        + "and currency_code = 'GOLD'",
                Long.class, account.id())).isZero();
    }

    @Test
    void submissionUsesRulesSnapshotCapturedWhenWindowOpened() {
        PlayerAccount account = bootstrapped("offline-rules-snapshot");
        OfflineBattleWindowResult window = service.sync(
                account.id(), UUID.randomUUID());
        makeElapsedAvailable(account.id());
        jdbc.update("update offline_play_budgets set rules_snapshot = "
                        + "jsonb_set(rules_snapshot, '{stages,0,clearGold}', '777') "
                        + "where account_id = ?",
                account.id());

        OfflineBattleSubmissionResult result = service.submit(
                account.id(), UUID.randomUUID(),
                submission(window.windowId(), UUID.randomUUID()));

        assertThat(result.rewardState()).isEqualTo(BattleRewardState.HELD);
        assertThat(result.economy().currencies()).containsEntry("GOLD", 0L);
        assertThat(jdbc.queryForObject("""
                select d.gold from offline_battle_decisions d
                  join offline_battle_runs r on r.id = d.run_id
                 where r.submission_id = ?
                """, Long.class, result.submissionId())).isEqualTo(777L);
        assertThat(jdbc.queryForObject(
                "select rules_version from offline_play_budgets where account_id = ?",
                String.class, account.id())).isEqualTo("unity-stage-2026-08-16");
    }

    @Test
    void databaseRejectsRunWhoseAccountDoesNotOwnSubmission() {
        PlayerAccount owner = bootstrapped("offline-owner");
        PlayerAccount other = bootstrapped("offline-other");
        OfflineBattleWindowResult window = service.sync(
                owner.id(), UUID.randomUUID());
        makeElapsedAvailable(owner.id());
        OfflineBattleSubmissionResult result = service.submit(
                owner.id(), UUID.randomUUID(),
                submission(window.windowId(), UUID.randomUUID()));

        assertThatThrownBy(() -> jdbc.update("""
                insert into offline_battle_runs(
                    id, submission_id, account_id, run_id, stage_code,
                    outcome, elapsed_seconds, kill_count, total_damage,
                    reached_wave, metrics_payload)
                values (?, ?, ?, ?, 'STAGE_1', 'CLEAR', 300, 100, 1000, 16, '{}'::jsonb)
                """, UUID.randomUUID(), result.submissionId(), other.id(),
                UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlineAndOfflineCompletionShareOneAccountSerializationLock()
            throws Exception {
        PlayerAccount account = bootstrapped("online-offline-concurrent");
        OfflineBattleWindowResult window = service.sync(
                account.id(), UUID.randomUUID());
        makeElapsedAvailable(account.id());
        var online = battleService.start(account.id(), UUID.randomUUID(),
                new BattleStartCommand("STAGE_1", "test-cross-flow"));
        jdbc.update("update battle_sessions set started_at = now() - interval '10 seconds' "
                + "where id = ?", online.battleId());

        Callable<Object> completeOnline = () -> battleService.complete(
                account.id(), online.battleId(), UUID.randomUUID(),
                new BattleCompletionCommand(
                        BattleOutcome.CLEAR, 10, 100, BigDecimal.valueOf(1000),
                        16, java.time.Instant.now()));
        Callable<Object> completeOffline = () -> service.submit(
                account.id(), UUID.randomUUID(),
                submission(window.windowId(), UUID.randomUUID()));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(
                    List.of(completeOnline, completeOffline), 5, TimeUnit.SECONDS);
            assertThat(futures).allMatch(future -> !future.isCancelled());
            for (var future : futures) future.get();
        }
        assertThat(jdbc.queryForObject(
                "select balance from player_wallets where account_id = ? "
                        + "and currency_code = 'GOLD'",
                Long.class, account.id())).isEqualTo(1000L);
    }

    private PlayerAccount bootstrapped(String subject) {
        PlayerAccount account = accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.GOOGLE, subject));
        economyService.bootstrap(account.id(), UUID.randomUUID(),
                new EconomyBootstrapCommand(Map.of("GOLD", 0L), Map.of(), List.of()));
        return account;
    }

    private void makeElapsedAvailable(UUID accountId) {
        jdbc.update("update offline_play_budgets set opened_at = now() - interval '10 minutes' "
                + "where account_id = ?", accountId);
    }

    private OfflineBattleSubmissionCommand submission(UUID windowId, UUID runId) {
        return submissionWithElapsed(windowId, runId, 300);
    }

    private OfflineBattleSubmissionCommand submissionWithElapsed(
            UUID windowId, UUID runId, int elapsedSeconds) {
        return new OfflineBattleSubmissionCommand(windowId, List.of(
                new OfflineBattleRunCommand(
                        runId, "STAGE_1", BattleOutcome.CLEAR,
                        elapsedSeconds, 100, BigDecimal.valueOf(1000), 16)));
    }
}
