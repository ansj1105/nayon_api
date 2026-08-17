package com.nayon.api.integration;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.economy.EconomyBootstrapCommand;
import com.nayon.api.economy.EconomyService;
import com.nayon.api.limitedbenefit.LimitedBenefitCampaign;
import com.nayon.api.limitedbenefit.LimitedBenefitClaimResult;
import com.nayon.api.limitedbenefit.LimitedBenefitException;
import com.nayon.api.limitedbenefit.LimitedBenefitService;
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
class LimitedBenefitPostgresTest {
    @Autowired AccountService accountService;
    @Autowired EconomyService economyService;
    @Autowired LimitedBenefitService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table player_limited_benefit_claims, admob_reward_callbacks, limited_benefit_ad_sessions, player_first_purchase_rewards, store_purchase_receipts, store_product_versions, store_products, player_account_link_rewards, player_korion_wallet_links, korion_wallet_link_requests, player_share_rewards, player_settings, "
                + "offline_battle_decisions, offline_battle_runs, offline_battle_submissions, "
                + "offline_play_window_requests, offline_play_budgets, battle_rewards, "
                + "battle_anomalies, battle_completions, battle_sessions, player_progression, "
                + "gacha_draw_results, gacha_draws, gacha_pity_states, economy_bootstraps, "
                + "economy_ledger, player_equipment, player_items, player_wallets, save_imports, "
                + "player_save_states, auth_identities, player_accounts");
        jdbc.update("""
                update limited_benefit_offers
                   set fulfillment_type = 'GOOGLE_PLAY',
                       store_offer_id = '00000000-0000-0000-0000-000000009201'
                 where offer_code = 'paid_3000_a'
                """);
    }

    @Test
    void catalogUsesServerCycleAndBlocksUnconfiguredProvider() {
        PlayerAccount account = bootstrappedAccount("limited-catalog");

        LimitedBenefitCampaign campaign = service.current(account.id()).orElseThrow();

        assertThat(campaign.offers()).hasSize(24);
        assertThat(campaign.resetsAt()).isAfter(campaign.serverTime());
        assertThat(campaign.offers().getFirst().state()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(campaign.offers().get(1).state()).isEqualTo("LOCKED");
    }

    @Test
    void freeClaimsAreSequentialAccountScopedAndExactlyOnce() {
        makeFirstOfferFreeForTest();
        PlayerAccount first = bootstrappedAccount("limited-first");
        PlayerAccount second = bootstrappedAccount("limited-second");
        UUID requestId = UUID.randomUUID();

        LimitedBenefitClaimResult claimed =
                service.claimFree(first.id(), requestId, "paid_3000_a");
        LimitedBenefitClaimResult replay =
                service.claimFree(first.id(), requestId, "paid_3000_a");

        assertThat(claimed.replay()).isFalse();
        assertThat(replay.replay()).isTrue();
        assertThat(replay.claimId()).isEqualTo(claimed.claimId());
        assertThat(replay.economy().currencies()).containsEntry("DIAMOND", 260L);
        assertThat(replay.economy().items())
                .containsEntry("SILVER_KEY", 8L)
                .containsEntry("RANDOM_SCROLL", 1L);
        assertThat(service.current(first.id()).orElseThrow().offers().get(1).state())
                .isEqualTo("AVAILABLE");
        assertThat(service.current(second.id()).orElseThrow().offers().getFirst().state())
                .isEqualTo("AVAILABLE");
        assertThatThrownBy(() ->
                service.claimFree(first.id(), UUID.randomUUID(), "paid_3000_a"))
                .isInstanceOf(LimitedBenefitException.class)
                .hasMessageContaining("current state");
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'LIMITED_BENEFIT'
                """, Long.class, first.id())).isEqualTo(3L);
    }

    @Test
    void concurrentDifferentKeysMintOneBundle() throws Exception {
        makeFirstOfferFreeForTest();
        PlayerAccount account = bootstrappedAccount("limited-concurrent");
        Callable<Boolean> claim = () -> {
            try {
                service.claimFree(account.id(), UUID.randomUUID(), "paid_3000_a");
                return true;
            } catch (LimitedBenefitException exception) {
                return false;
            }
        };

        List<Boolean> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            outcomes = executor.invokeAll(List.of(claim, claim)).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForObject("""
                select count(*) from player_limited_benefit_claims where account_id = ?
                """, Long.class, account.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'LIMITED_BENEFIT'
                """, Long.class, account.id())).isEqualTo(3L);
    }

    private PlayerAccount bootstrappedAccount(String subject) {
        PlayerAccount account = accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.GOOGLE, subject));
        economyService.bootstrap(account.id(), UUID.randomUUID(),
                new EconomyBootstrapCommand(
                        Map.of("DIAMOND", 100L), Map.of(), List.of()));
        return account;
    }

    private void makeFirstOfferFreeForTest() {
        jdbc.update("""
                update limited_benefit_offers
                   set fulfillment_type = 'FREE', store_offer_id = null
                 where offer_code = 'paid_3000_a'
                """);
    }
}
