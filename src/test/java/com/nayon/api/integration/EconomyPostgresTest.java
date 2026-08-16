package com.nayon.api.integration;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.economy.EconomyBootstrapCommand;
import com.nayon.api.economy.EconomyBootstrapEquipment;
import com.nayon.api.economy.EconomyBootstrapResult;
import com.nayon.api.economy.EconomyService;
import com.nayon.api.economy.EconomySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
class EconomyPostgresTest {

    @Autowired
    AccountService accountService;

    @Autowired
    EconomyService economyService;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table player_share_rewards, player_settings, "
                + "offline_battle_decisions, offline_battle_runs, "
                + "offline_battle_submissions, offline_play_window_requests, "
                + "offline_play_budgets, battle_rewards, battle_anomalies, battle_completions, "
                + "battle_sessions, player_progression, "
                + "gacha_draw_results, gacha_draws, gacha_pity_states, "
                + "economy_bootstraps, economy_ledger, "
                + "player_equipment, player_items, player_wallets, "
                + "save_imports, player_save_states, auth_identities, player_accounts");
    }

    @Test
    void bootstrapPersistsBalancesEquipmentLedgerAndReplay() {
        PlayerAccount account = account("postgres-economy");
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");

        EconomyBootstrapResult first = economyService.bootstrap(
                account.id(), requestId, command());
        EconomyBootstrapResult replay = economyService.bootstrap(
                account.id(), requestId, command());
        EconomySnapshot loaded = economyService.get(account.id());

        assertThat(first.replay()).isFalse();
        assertThat(replay.replay()).isTrue();
        assertThat(loaded).isEqualTo(first.snapshot());
        assertThat(loaded.currencies()).containsEntry("DIAMOND", 250L);
        assertThat(loaded.items()).containsEntry("SILVER_KEY", 3L);
        assertThat(loaded.equipment()).hasSize(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from economy_ledger where account_id = ?",
                Long.class, account.id())).isEqualTo(2L);
    }

    @Test
    void concurrentIdenticalBootstrapWritesExactlyOnce() throws Exception {
        PlayerAccount account = account("postgres-concurrent");
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        Callable<EconomyBootstrapResult> call =
                () -> economyService.bootstrap(account.id(), requestId, command());

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<EconomyBootstrapResult> results = executor.invokeAll(List.of(call, call))
                    .stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();
            assertThat(results).extracting(EconomyBootstrapResult::replay)
                    .containsExactlyInAnyOrder(false, true);
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from economy_bootstraps where account_id = ?",
                Long.class, account.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select count(*) from player_equipment where account_id = ?",
                Long.class, account.id())).isEqualTo(2L);
    }

    @Test
    void economyRowsRemainAccountIsolated() {
        PlayerAccount accountA = account("postgres-isolated-a");
        PlayerAccount accountB = account("postgres-isolated-b");
        economyService.bootstrap(accountA.id(), UUID.randomUUID(), command());

        assertThat(economyService.get(accountB.id()))
                .isEqualTo(EconomySnapshot.empty(accountB.id()));
    }

    private PlayerAccount account(String subject) {
        return accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.GOOGLE, subject));
    }

    private EconomyBootstrapCommand command() {
        return new EconomyBootstrapCommand(
                Map.of("DIAMOND", 250L),
                Map.of("SILVER_KEY", 3L),
                List.of(new EconomyBootstrapEquipment("Weapon_01", "COMMON", 2)));
    }
}
