package com.nayon.api.integration;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.economy.EconomyBootstrapCommand;
import com.nayon.api.economy.EconomyService;
import com.nayon.api.share.ShareRewardResult;
import com.nayon.api.share.ShareRewardService;
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
class ShareRewardPostgresTest {

    @Autowired
    AccountService accountService;

    @Autowired
    EconomyService economyService;

    @Autowired
    ShareRewardService shareRewardService;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table store_purchase_receipts, store_product_versions, store_products, player_account_link_rewards, player_korion_wallet_links, korion_wallet_link_requests, player_share_rewards, player_settings, "
                + "offline_battle_decisions, offline_battle_runs, "
                + "offline_battle_submissions, offline_play_window_requests, "
                + "offline_play_budgets, "
                + "battle_rewards, battle_anomalies, battle_completions, battle_sessions, "
                + "player_progression, gacha_draw_results, gacha_draws, gacha_pity_states, "
                + "economy_bootstraps, economy_ledger, player_equipment, player_items, "
                + "player_wallets, save_imports, player_save_states, auth_identities, "
                + "player_accounts");
    }

    @Test
    void claimPersistsLifetimeStateAndOneLedgerCredit() {
        PlayerAccount account = bootstrappedAccount("share-postgres");
        shareRewardService.markOpened(account.id(), "com.kakao.talk");

        UUID requestId = UUID.randomUUID();
        ShareRewardResult first = shareRewardService.claim(account.id(), requestId);
        ShareRewardResult sameRequestReplay = shareRewardService.claim(account.id(), requestId);
        ShareRewardResult replay = shareRewardService.claim(account.id(), UUID.randomUUID());

        assertThat(first.state().rewardClaimed()).isTrue();
        assertThat(sameRequestReplay.economy().currencies())
                .containsEntry("DIAMOND", 150L);
        assertThat(replay.economy().currencies()).containsEntry("DIAMOND", 150L);
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ?
                   and reason_code = 'SHARE_REWARD'
                   and reference_type = 'PLAYER_SHARE_REWARD'
                   and asset_code = 'DIAMOND'
                   and delta = 50
                """, Long.class, account.id())).isEqualTo(1L);
    }

    @Test
    void concurrentClaimsCreditExactlyOnce() throws Exception {
        PlayerAccount account = bootstrappedAccount("share-concurrent");
        shareRewardService.markOpened(account.id(), null);
        Callable<ShareRewardResult> first =
                () -> shareRewardService.claim(account.id(), UUID.randomUUID());
        Callable<ShareRewardResult> second =
                () -> shareRewardService.claim(account.id(), UUID.randomUUID());

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<ShareRewardResult> results = executor.invokeAll(List.of(first, second))
                    .stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();
            assertThat(results).allSatisfy(result ->
                    assertThat(result.economy().currencies())
                            .containsEntry("DIAMOND", 150L));
        }

        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'SHARE_REWARD'
                """, Long.class, account.id())).isEqualTo(1L);
    }

    @Test
    void separateAccountsEachOwnTheirLifetimeState() {
        PlayerAccount first = bootstrappedAccount("share-isolated-a");
        PlayerAccount second = bootstrappedAccount("share-isolated-b");

        shareRewardService.markOpened(first.id(), null);
        shareRewardService.claim(first.id(), UUID.randomUUID());

        assertThat(shareRewardService.get(first.id()).state().rewardClaimed()).isTrue();
        assertThat(shareRewardService.get(second.id()).state().shared()).isFalse();
        assertThat(shareRewardService.get(second.id()).economy().currencies())
                .containsEntry("DIAMOND", 100L);
    }

    private PlayerAccount bootstrappedAccount(String subject) {
        PlayerAccount account = accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.GOOGLE, subject));
        economyService.bootstrap(
                account.id(),
                UUID.randomUUID(),
                new EconomyBootstrapCommand(
                        Map.of("DIAMOND", 100L), Map.of(), List.of()));
        return account;
    }
}
