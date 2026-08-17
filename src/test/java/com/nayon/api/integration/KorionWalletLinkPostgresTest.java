package com.nayon.api.integration;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.accountlink.AccountLinkRewardResult;
import com.nayon.api.accountlink.AccountLinkRewardException;
import com.nayon.api.accountlink.AccountLinkRewardService;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.economy.EconomyBootstrapCommand;
import com.nayon.api.economy.EconomyService;
import com.nayon.api.korion.KorionWalletGateway;
import com.nayon.api.korion.KorionWalletLinkService;
import com.nayon.api.korion.KorionWalletLinkStatus;
import com.nayon.api.korion.KorionWalletLinkView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "management.health.db.enabled=false")
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class KorionWalletLinkPostgresTest {
    private static final String ADDRESS = "TJRabPrwbZy45sbavfcjinPJC18kjpRTv8";

    @Autowired AccountService accountService;
    @Autowired EconomyService economyService;
    @Autowired KorionWalletLinkService walletLinkService;
    @Autowired AccountLinkRewardService rewardService;
    @Autowired FakeGateway gateway;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        gateway.status = KorionWalletLinkStatus.PENDING;
        jdbc.execute("truncate table store_purchase_receipts, store_product_versions, store_products, player_account_link_rewards, player_korion_wallet_links, "
                + "korion_wallet_link_requests, player_share_rewards, player_settings, "
                + "offline_battle_decisions, offline_battle_runs, offline_battle_submissions, "
                + "offline_play_window_requests, offline_play_budgets, battle_rewards, "
                + "battle_anomalies, battle_completions, battle_sessions, player_progression, "
                + "gacha_draw_results, gacha_draws, gacha_pity_states, economy_bootstraps, "
                + "economy_ledger, player_equipment, player_items, player_wallets, save_imports, "
                + "player_save_states, auth_identities, player_accounts");
    }

    @Test
    void approvalCreatesLinkAndUnlinkBlocksDelayedApproval() {
        PlayerAccount account = account("wallet-link");
        KorionWalletLinkView created = walletLinkService.create(account.id(), ADDRESS);
        gateway.status = KorionWalletLinkStatus.APPROVED;
        KorionWalletLinkView linked = walletLinkService.reconcile(account.id(), created.requestId());

        assertThat(linked.linked()).isTrue();
        walletLinkService.unlink(account.id());
        assertThat(walletLinkService.get(account.id()).linked()).isFalse();
        assertThat(walletLinkService.reconcile(account.id(), created.requestId()).linked()).isFalse();
    }

    @Test
    void dualLinkRewardCreditsThreeAssetsExactlyOnce() {
        PlayerAccount account = account("dual-reward");
        economyService.bootstrap(account.id(), UUID.randomUUID(),
                new EconomyBootstrapCommand(Map.of("DIAMOND", 10L), Map.of(), List.of()));
        KorionWalletLinkView request = walletLinkService.create(account.id(), ADDRESS);
        gateway.status = KorionWalletLinkStatus.APPROVED;
        walletLinkService.reconcile(account.id(), request.requestId());

        UUID idempotencyKey = UUID.randomUUID();
        AccountLinkRewardResult first = rewardService.claim(account.id(), idempotencyKey);
        AccountLinkRewardResult sameKeyReplay = rewardService.claim(account.id(), idempotencyKey);
        AccountLinkRewardResult replay = rewardService.claim(account.id(), UUID.randomUUID());

        assertThat(first.economy().currencies()).containsEntry("DIAMOND", 310L);
        assertThat(sameKeyReplay.state().rewardClaimed()).isTrue();
        assertThat(replay.economy().items())
                .containsEntry("SILVER_KEY", 1L)
                .containsEntry("GOLD_KEY", 1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'ACCOUNT_LINK_REWARD'
                """, Long.class, account.id())).isEqualTo(3L);
    }

    @Test
    void concurrentClaimsStillCreditOnlyThreeLedgerEntries() throws Exception {
        PlayerAccount account = account("dual-reward-concurrent");
        economyService.bootstrap(account.id(), UUID.randomUUID(),
                new EconomyBootstrapCommand(Map.of("DIAMOND", 10L), Map.of(), List.of()));
        KorionWalletLinkView request = walletLinkService.create(account.id(), ADDRESS);
        gateway.status = KorionWalletLinkStatus.APPROVED;
        walletLinkService.reconcile(account.id(), request.requestId());
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return rewardService.claim(account.id(), UUID.randomUUID());
            });
            var second = executor.submit(() -> {
                start.await();
                return rewardService.claim(account.id(), UUID.randomUUID());
            });
            start.countDown();
            assertThat(first.get().state().rewardClaimed()).isTrue();
            assertThat(second.get().state().rewardClaimed()).isTrue();
        }

        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'ACCOUNT_LINK_REWARD'
                """, Long.class, account.id())).isEqualTo(3L);
    }

    @Test
    void claimRequiresVerifiedKorionLinkAndDoesNotCreditAnotherAccount() {
        PlayerAccount eligible = account("dual-reward-isolation-eligible");
        PlayerAccount unlinked = account("dual-reward-isolation-unlinked");
        economyService.bootstrap(eligible.id(), UUID.randomUUID(),
                new EconomyBootstrapCommand(Map.of("DIAMOND", 10L), Map.of(), List.of()));
        economyService.bootstrap(unlinked.id(), UUID.randomUUID(),
                new EconomyBootstrapCommand(Map.of("DIAMOND", 20L), Map.of(), List.of()));
        KorionWalletLinkView request = walletLinkService.create(eligible.id(), ADDRESS);
        gateway.status = KorionWalletLinkStatus.APPROVED;
        walletLinkService.reconcile(eligible.id(), request.requestId());

        rewardService.claim(eligible.id(), UUID.randomUUID());

        assertThatThrownBy(() -> rewardService.claim(unlinked.id(), UUID.randomUUID()))
                .isInstanceOf(AccountLinkRewardException.class)
                .extracting(error -> ((AccountLinkRewardException) error).code())
                .isEqualTo("KORION_LINK_REQUIRED");
        assertThat(jdbc.queryForObject("""
                select balance from player_wallets
                 where account_id = ? and currency_code = 'DIAMOND'
                """, Long.class, unlinked.id())).isEqualTo(20L);
    }

    private PlayerAccount account(String subject) {
        return accountService.resolveOrCreate(new AuthenticatedIdentity(AuthProvider.GOOGLE, subject));
    }

    @TestConfiguration
    static class GatewayConfig {
        @Bean
        @Primary
        FakeGateway fakeKorionWalletGateway() {
            return new FakeGateway();
        }
    }

    static final class FakeGateway implements KorionWalletGateway {
        volatile KorionWalletLinkStatus status = KorionWalletLinkStatus.PENDING;

        @Override
        public GatewayResult create(UUID requestId, String address) {
            return new GatewayResult(requestId, address, status,
                    Instant.now().plusSeconds(600), true);
        }

        @Override
        public GatewayResult get(UUID requestId) {
            return new GatewayResult(requestId, ADDRESS, status, Instant.now().plusSeconds(600), null);
        }
    }
}
