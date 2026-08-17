package com.nayon.api.integration;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.economy.EconomyBootstrapCommand;
import com.nayon.api.economy.EconomyService;
import com.nayon.api.gacha.GachaBanner;
import com.nayon.api.gacha.GachaDrawCommand;
import com.nayon.api.gacha.GachaDrawResult;
import com.nayon.api.gacha.GachaHistoryPage;
import com.nayon.api.gacha.GachaPayment;
import com.nayon.api.gacha.GachaService;
import com.nayon.api.gacha.InsufficientAssetException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "management.health.db.enabled=false")
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class GachaPostgresTest {
    @Autowired AccountService accountService;
    @Autowired EconomyService economyService;
    @Autowired GachaService gachaService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table player_limited_benefit_claims, admob_reward_callbacks, limited_benefit_ad_sessions, player_first_purchase_rewards, store_purchase_receipts, store_product_versions, store_products, player_account_link_rewards, player_korion_wallet_links, korion_wallet_link_requests, player_share_rewards, player_settings, "
                + "offline_battle_decisions, offline_battle_runs, "
                + "offline_battle_submissions, offline_play_window_requests, "
                + "offline_play_budgets, battle_rewards, battle_anomalies, battle_completions, "
                + "battle_sessions, player_progression, "
                + "gacha_draw_results, gacha_draws, gacha_pity_states, "
                + "economy_bootstraps, economy_ledger, player_equipment, player_items, "
                + "player_wallets, save_imports, player_save_states, auth_identities, "
                + "player_accounts");
    }

    @Test
    void paidDrawDebitsOnceAndPersistsResultLedgerEquipmentAndReplay() {
        PlayerAccount account = bootstrapped("gacha-paid", 2, 0, 0, 0);
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000501");
        GachaDrawCommand command = new GachaDrawCommand(
                GachaBanner.COMMON, GachaPayment.SILVER_KEY, 1);

        GachaDrawResult first = gachaService.draw(account.id(), requestId, command);
        GachaDrawResult replay = gachaService.draw(account.id(), requestId, command);

        assertThat(first.replay()).isFalse();
        assertThat(replay.replay()).isTrue();
        assertThat(replay.drawId()).isEqualTo(first.drawId());
        assertThat(first.economy().items()).containsEntry("SILVER_KEY", 1L);
        assertThat(jdbc.queryForObject(
                "select count(*) from gacha_draws where account_id = ?",
                Long.class, account.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select count(*) from gacha_draw_results where draw_id = ?",
                Long.class, first.drawId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select count(*) from economy_ledger where reference_id = ?",
                Long.class, first.drawId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select count(*) from player_equipment where source_id = ?",
                Long.class, first.drawId())).isEqualTo(1L);
    }

    @Test
    void insufficientAssetRollsBackAllGachaRows() {
        PlayerAccount account = bootstrapped("gacha-empty", 0, 0, 0, 0);

        assertThatThrownBy(() -> gachaService.draw(
                account.id(), UUID.randomUUID(),
                new GachaDrawCommand(
                        GachaBanner.COMMON, GachaPayment.SILVER_KEY, 1)))
                .isInstanceOf(InsufficientAssetException.class);
        assertThat(jdbc.queryForObject("select count(*) from gacha_draws", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from player_equipment where source_type = 'GACHA'",
                Long.class)).isZero();
    }

    @Test
    void concurrentIdenticalDrawChargesExactlyOnce() throws Exception {
        PlayerAccount account = bootstrapped("gacha-concurrent", 2, 0, 0, 0);
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000502");
        GachaDrawCommand command = new GachaDrawCommand(
                GachaBanner.COMMON, GachaPayment.SILVER_KEY, 1);
        Callable<GachaDrawResult> call =
                () -> gachaService.draw(account.id(), requestId, command);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<GachaDrawResult> results = executor.invokeAll(List.of(call, call))
                    .stream().map(future -> {
                        try { return future.get(); }
                        catch (Exception exception) { throw new AssertionError(exception); }
                    }).toList();
            assertThat(results).extracting(GachaDrawResult::replay)
                    .containsExactlyInAnyOrder(false, true);
        }
        assertThat(economyService.get(account.id()).items())
                .containsEntry("SILVER_KEY", 1L);
    }

    @Test
    void historyIsAccountIsolatedAndCursorPaged() {
        PlayerAccount accountA = bootstrapped("gacha-history-a", 3, 0, 0, 0);
        PlayerAccount accountB = bootstrapped("gacha-history-b", 1, 0, 0, 0);
        GachaDrawCommand command = new GachaDrawCommand(
                GachaBanner.COMMON, GachaPayment.SILVER_KEY, 1);
        gachaService.draw(accountA.id(), UUID.randomUUID(), command);
        gachaService.draw(accountA.id(), UUID.randomUUID(), command);
        gachaService.draw(accountB.id(), UUID.randomUUID(), command);

        GachaHistoryPage first = gachaService.history(accountA.id(), null, 1);
        GachaHistoryPage second = gachaService.history(
                accountA.id(), first.nextCursor(), 1);

        assertThat(first.draws()).hasSize(1);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(second.draws()).hasSize(1);
        assertThat(second.draws().getFirst().drawId())
                .isNotEqualTo(first.draws().getFirst().drawId());
        assertThat(gachaService.history(accountB.id(), null, 20).draws()).hasSize(1);
    }

    private PlayerAccount bootstrapped(
            String subject, long silver, long gold, long fragments, long diamonds) {
        PlayerAccount account = accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.GOOGLE, subject));
        economyService.bootstrap(account.id(), UUID.randomUUID(),
                new EconomyBootstrapCommand(
                        Map.of("DIAMOND", diamonds),
                        Map.of("SILVER_KEY", silver, "GOLD_KEY", gold,
                                "CHROMA_FRAGMENT", fragments),
                        List.of()));
        return account;
    }
}
