package com.nayon.api.integration;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.battle.BattleCompletionCommand;
import com.nayon.api.battle.BattleCompletionResult;
import com.nayon.api.battle.BattleOutcome;
import com.nayon.api.battle.BattleRewardState;
import com.nayon.api.battle.BattleService;
import com.nayon.api.battle.BattleSessionResult;
import com.nayon.api.battle.BattleStartCommand;
import com.nayon.api.economy.EconomyBootstrapCommand;
import com.nayon.api.economy.EconomyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "management.health.db.enabled=false")
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class BattlePostgresTest {
    @Autowired AccountService accountService;
    @Autowired EconomyService economyService;
    @Autowired BattleService battleService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table offline_battle_decisions, offline_battle_runs, "
                + "offline_battle_submissions, offline_play_window_requests, "
                + "offline_play_budgets, battle_rewards, battle_anomalies, battle_completions, "
                + "battle_sessions, player_progression, gacha_draw_results, gacha_draws, "
                + "gacha_pity_states, economy_bootstraps, economy_ledger, player_equipment, "
                + "player_items, player_wallets, save_imports, player_save_states, "
                + "auth_identities, player_accounts");
    }

    @Test
    void validClearGrantsGoldAndExperienceExactlyOnce() {
        PlayerAccount account = bootstrapped("battle-valid");
        BattleSessionResult session = start(account, "valid");
        makeServerObservedDurationValid(session.battleId());
        UUID requestId = UUID.randomUUID();
        BattleCompletionCommand command = validClear();

        BattleCompletionResult first = battleService.complete(
                account.id(), session.battleId(), requestId, command);
        BattleCompletionResult replay = battleService.complete(
                account.id(), session.battleId(), requestId, command);

        assertThat(first.rewardState()).isEqualTo(BattleRewardState.GRANTED);
        assertThat(replay.replay()).isTrue();
        assertThat(first.economy().currencies()).containsEntry("GOLD", 1000L);
        assertThat(first.totalAccountExp()).isEqualTo(187L);
        assertThat(first.economy().items())
                .containsEntry("RANDOM_SCROLL", 10L)
                .containsEntry("LEVEL_UP_COUPON", 1L);
        assertThat(jdbc.queryForObject(
                "select count(*) from economy_ledger where reference_id = ?",
                Long.class, session.battleId())).isEqualTo(3L);
    }

    @Test
    void suspiciousMetricsAreDurableAndRewardIsHeldWithoutMinting() {
        PlayerAccount account = bootstrapped("battle-held");
        BattleSessionResult session = start(account, "held");
        BattleCompletionResult result = battleService.complete(
                account.id(), session.battleId(), UUID.randomUUID(),
                new BattleCompletionCommand(
                        BattleOutcome.CLEAR, -1, -1, BigDecimal.valueOf(-1),
                        99, Instant.now()));

        assertThat(result.rewardState()).isEqualTo(BattleRewardState.HELD);
        assertThat(result.anomalyReasons()).contains(
                "ELAPSED_NEGATIVE", "KILL_COUNT_NEGATIVE",
                "DAMAGE_NEGATIVE", "WAVE_ABOVE_MAX", "CLEAR_TOO_FAST");
        assertThat(result.economy().currencies()).containsEntry("GOLD", 0L);
        assertThat(jdbc.queryForObject(
                "select count(*) from battle_anomalies where battle_id = ?",
                Long.class, session.battleId())).isGreaterThanOrEqualTo(5L);
    }

    @Test
    void concurrentIdenticalCompletionGrantsOnce() throws Exception {
        PlayerAccount account = bootstrapped("battle-concurrent");
        BattleSessionResult session = start(account, "concurrent");
        makeServerObservedDurationValid(session.battleId());
        UUID requestId = UUID.randomUUID();
        BattleCompletionCommand command = validClear();
        Callable<BattleCompletionResult> call = () -> battleService.complete(
                account.id(), session.battleId(), requestId, command);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<BattleCompletionResult> results = executor.invokeAll(List.of(call, call))
                    .stream().map(future -> {
                        try { return future.get(); }
                        catch (Exception exception) { throw new AssertionError(exception); }
                    }).toList();
            assertThat(results).extracting(BattleCompletionResult::replay)
                    .containsExactlyInAnyOrder(false, true);
        }
        assertThat(jdbc.queryForObject(
                "select balance from player_wallets where account_id = ? and currency_code = 'GOLD'",
                Long.class, account.id())).isEqualTo(1000L);
    }

    @Test
    void historyIsAccountIsolated() {
        PlayerAccount accountA = bootstrapped("battle-history-a");
        PlayerAccount accountB = bootstrapped("battle-history-b");
        BattleSessionResult session = start(accountA, "history");
        makeServerObservedDurationValid(session.battleId());
        battleService.complete(accountA.id(), session.battleId(),
                UUID.randomUUID(), validClear());

        assertThat(battleService.history(accountA.id(), null, 20).battles()).hasSize(1);
        assertThat(battleService.history(accountB.id(), null, 20).battles()).isEmpty();
    }

    private PlayerAccount bootstrapped(String subject) {
        PlayerAccount account = accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.GOOGLE, subject));
        economyService.bootstrap(account.id(), UUID.randomUUID(),
                new EconomyBootstrapCommand(
                        Map.of("GOLD", 0L), Map.of(), List.of()));
        return account;
    }

    private BattleSessionResult start(PlayerAccount account, String suffix) {
        return battleService.start(account.id(), UUID.randomUUID(),
                new BattleStartCommand("STAGE_1", "test-" + suffix));
    }

    private void makeServerObservedDurationValid(UUID battleId) {
        jdbc.update("update battle_sessions set started_at = now() - interval '10 seconds' "
                + "where id = ?", battleId);
    }

    private BattleCompletionCommand validClear() {
        return new BattleCompletionCommand(
                BattleOutcome.CLEAR, 10, 100, BigDecimal.valueOf(1000),
                16, Instant.now());
    }
}
