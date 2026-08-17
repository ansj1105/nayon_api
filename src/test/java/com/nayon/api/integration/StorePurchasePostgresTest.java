package com.nayon.api.integration;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.economy.EconomyBootstrapCommand;
import com.nayon.api.economy.EconomyService;
import com.nayon.api.store.StoreAccountHasher;
import com.nayon.api.store.StorePurchaseCommand;
import com.nayon.api.store.StorePurchaseException;
import com.nayon.api.store.StorePurchaseResult;
import com.nayon.api.store.StorePurchaseService;
import com.nayon.api.store.StorePurchaseState;
import com.nayon.api.store.google.GooglePlayGatewayException;
import com.nayon.api.store.google.GooglePlayPurchase;
import com.nayon.api.store.google.GooglePlayPurchaseGateway;
import com.nayon.api.store.google.GooglePlayPurchaseState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "management.health.db.enabled=false",
                "nayon.store.account-hash-key=test-only-account-hash-key"
        })
@Import(StorePurchasePostgresTest.FakeConfig.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class StorePurchasePostgresTest {

    @Autowired AccountService accountService;
    @Autowired EconomyService economyService;
    @Autowired StorePurchaseService service;
    @Autowired StoreAccountHasher accountHasher;
    @Autowired FakeGooglePlayGateway gateway;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table player_limited_benefit_claims, admob_reward_callbacks, limited_benefit_ad_sessions, player_first_purchase_rewards, store_purchase_receipts, store_product_versions, "
                + "store_products, player_account_link_rewards, player_korion_wallet_links, "
                + "korion_wallet_link_requests, player_share_rewards, player_settings, "
                + "offline_battle_decisions, offline_battle_runs, offline_battle_submissions, "
                + "offline_play_window_requests, offline_play_budgets, battle_rewards, "
                + "battle_anomalies, battle_completions, battle_sessions, player_progression, "
                + "gacha_draw_results, gacha_draws, gacha_pity_states, economy_bootstraps, "
                + "economy_ledger, player_equipment, player_items, player_wallets, save_imports, "
                + "player_save_states, auth_identities, player_accounts");
        jdbc.update("delete from first_purchase_reward_versions where version <> 1");
        jdbc.update("""
                update first_purchase_reward_versions
                   set active = true, valid_until = null,
                       diamond_amount = 50, gold_amount = 10000
                 where version = 1
                """);
        gateway.clear();
        configureProduct();
    }

    @Test
    void verifiedPurchaseCreditsExactlyOnceAcrossRetries() {
        PlayerAccount account = bootstrappedAccount("store-exact-once");
        gateway.enqueue(purchased(account));
        UUID requestId = UUID.randomUUID();
        StorePurchaseCommand command = command("token-exact-once");

        StorePurchaseResult first = service.verify(account.id(), requestId, command);
        StorePurchaseResult sameKey = service.verify(account.id(), requestId, command);
        StorePurchaseResult newKey = service.verify(
                account.id(), UUID.randomUUID(), command);

        assertThat(first.receipt().state()).isEqualTo(StorePurchaseState.GRANTED);
        assertThat(first.receipt().totalAssetBalance()).isEqualTo(200L);
        assertThat(first.firstPurchaseReward()).isNotNull();
        assertThat(sameKey.firstPurchaseReward()).isNotNull();
        assertThat(newKey.firstPurchaseReward()).isNotNull();
        assertThat(sameKey.replay()).isTrue();
        assertThat(newKey.replay()).isTrue();
        assertThat(gateway.getCalls).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'STORE_PURCHASE'
                """, Long.class, account.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from player_first_purchase_rewards
                 where account_id = ? and qualifying_receipt_id = ?
                """, Long.class, account.id(), first.receipt().id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'FIRST_PURCHASE_REWARD'
                """, Long.class, account.id())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                select count(*) from player_equipment
                 where account_id = ? and source_type = 'FIRST_PURCHASE_REWARD'
                """, Long.class, account.id())).isEqualTo(1L);
    }

    @Test
    void laterPurchaseDoesNotReturnOrGrantFirstPurchaseRewardAgain() {
        PlayerAccount account = bootstrappedAccount("store-later-purchase");
        gateway.enqueue(purchased(account));
        gateway.enqueue(purchased(account));

        StorePurchaseResult first = service.verify(
                account.id(), UUID.randomUUID(), command("token-first"));
        StorePurchaseResult later = service.verify(
                account.id(), UUID.randomUUID(), command("token-later"));

        assertThat(first.firstPurchaseReward()).isNotNull();
        assertThat(later.firstPurchaseReward()).isNull();
        assertThat(jdbc.queryForObject("""
                select count(*) from player_first_purchase_rewards where account_id = ?
                """, Long.class, account.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'FIRST_PURCHASE_REWARD'
                """, Long.class, account.id())).isEqualTo(2L);
    }

    @Test
    void concurrentDifferentPurchasesGrantOneFirstPurchaseBundle() throws Exception {
        PlayerAccount account = bootstrappedAccount("store-concurrent-first-bundle");
        GooglePlayPurchase purchase = purchased(account);
        gateway.enqueue(purchase);
        gateway.enqueue(purchase);
        Callable<StorePurchaseResult> first = () -> service.verify(
                account.id(), UUID.randomUUID(), command("token-bundle-a"));
        Callable<StorePurchaseResult> second = () -> service.verify(
                account.id(), UUID.randomUUID(), command("token-bundle-b"));

        try (var executor = Executors.newFixedThreadPool(2)) {
            executor.invokeAll(List.of(first, second)).forEach(future -> {
                try {
                    assertThat(future.get().receipt().state())
                            .isEqualTo(StorePurchaseState.GRANTED);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
        }

        assertThat(jdbc.queryForObject("""
                select count(*) from player_first_purchase_rewards where account_id = ?
                """, Long.class, account.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from player_equipment
                 where account_id = ? and source_type = 'FIRST_PURCHASE_REWARD'
                """, Long.class, account.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'FIRST_PURCHASE_REWARD'
                """, Long.class, account.id())).isEqualTo(2L);
    }

    @Test
    void concurrentVerificationOfOneTokenStillCreditsOneLedgerEntry() throws Exception {
        PlayerAccount account = bootstrappedAccount("store-concurrent");
        GooglePlayPurchase purchase = purchased(account);
        gateway.enqueue(purchase);
        gateway.enqueue(purchase);
        Callable<StorePurchaseResult> first = () -> service.verify(
                account.id(), UUID.randomUUID(), command("token-concurrent"));
        Callable<StorePurchaseResult> second = () -> service.verify(
                account.id(), UUID.randomUUID(), command("token-concurrent"));

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<StorePurchaseResult> results = executor.invokeAll(List.of(first, second))
                    .stream().map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    }).toList();
            assertThat(results).allSatisfy(result ->
                    assertThat(result.receipt().totalAssetBalance()).isEqualTo(200L));
        }

        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'STORE_PURCHASE'
                """, Long.class, account.id())).isEqualTo(1L);
    }

    @Test
    void pendingGatewayOutcomeKeepsSameReceiptForSuccessfulRetry() {
        PlayerAccount account = bootstrappedAccount("store-pending");
        gateway.enqueue(new GooglePlayPurchase(
                List.of("nayon.diamond.100"), GooglePlayPurchaseState.PENDING,
                null, accountHasher.hash(account.id()), null, false));
        gateway.enqueue(purchased(account));
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(() -> service.verify(
                account.id(), requestId, command("token-pending")))
                .isInstanceOfSatisfying(StorePurchaseException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("GOOGLE_PLAY_PURCHASE_PENDING"));
        StorePurchaseResult retry = service.verify(
                account.id(), requestId, command("token-pending"));

        assertThat(retry.receipt().state()).isEqualTo(StorePurchaseState.GRANTED);
        assertThat(jdbc.queryForObject("select count(*) from store_purchase_receipts",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void accountMismatchAndCrossAccountTokenReuseNeverCredit() {
        PlayerAccount first = bootstrappedAccount("store-owner-a");
        PlayerAccount second = bootstrappedAccount("store-owner-b");
        gateway.enqueue(new GooglePlayPurchase(
                List.of("nayon.diamond.100"), GooglePlayPurchaseState.PURCHASED,
                "GPA.mismatch", accountHasher.hash(second.id()), Instant.now(), false));

        assertThatThrownBy(() -> service.verify(
                first.id(), UUID.randomUUID(), command("token-owned")))
                .isInstanceOfSatisfying(StorePurchaseException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("GOOGLE_PLAY_ACCOUNT_MISMATCH"));
        assertThatThrownBy(() -> service.verify(
                second.id(), UUID.randomUUID(), command("token-owned")))
                .isInstanceOfSatisfying(StorePurchaseException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("STORE_PURCHASE_TOKEN_CONFLICT"));
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger where reason_code = 'STORE_PURCHASE'
                """, Long.class)).isZero();
    }

    @Test
    void delayedTokenUsesRewardVersionActiveAtGooglePurchaseTime() {
        PlayerAccount account = bootstrappedAccount("store-historical-version");
        UUID productId = jdbc.queryForObject("""
                select id from store_products
                 where store_product_id = 'nayon.diamond.100'
                """, UUID.class);
        jdbc.update("""
                update store_product_versions
                   set active = false,
                       valid_until = '2026-08-18T00:00:00Z'
                 where product_id = ?
                """, productId);
        jdbc.update("""
                insert into store_product_versions(
                    id, product_id, version, reward_asset_type, reward_asset_code,
                    reward_amount, valid_from, active)
                values (?, ?, 2, 'CURRENCY', 'DIAMOND', 120,
                        '2026-08-18T00:00:00Z', true)
                """, UUID.randomUUID(), productId);
        jdbc.update("""
                update first_purchase_reward_versions
                   set active = false,
                       valid_until = '2026-08-18T00:00:00Z'
                 where version = 1
                """);
        jdbc.update("""
                insert into first_purchase_reward_versions(
                    id, version, equipment_catalog_version, equipment_grade,
                    diamond_amount, gold_amount, valid_from, active)
                values (?, 2, 'unity-equipment-2026-08-16', 'COMMON',
                        60, 12000, '2026-08-18T00:00:00Z', true)
                """, UUID.randomUUID());
        gateway.enqueue(purchased(account));

        StorePurchaseResult result = service.verify(
                account.id(), UUID.randomUUID(), command("token-historical"));

        assertThat(result.receipt().rewardVersion()).isEqualTo(1);
        assertThat(result.receipt().rewardAmount()).isEqualTo(100L);
        assertThat(result.receipt().totalAssetBalance()).isEqualTo(200L);
        assertThat(result.firstPurchaseReward().rewardVersion()).isEqualTo(1);
        assertThat(result.firstPurchaseReward().diamondAmount()).isEqualTo(50L);
    }

    @Test
    void authenticatedHttpContractReturnsCatalogAndCreatedPurchase() throws Exception {
        PlayerAccount account = bootstrappedAccount("store-http");
        gateway.enqueue(purchased(account));

        mvc.perform(get("/api/v1/store/first-purchase-reward")
                        .with(jwt().jwt(token -> token.subject("store-http")
                                .claim("nayon:provider", "GOOGLE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_GRANTED"));

        mvc.perform(get("/api/v1/store/catalog")
                        .param("platform", "GOOGLE_PLAY")
                        .with(jwt().jwt(token -> token.subject("store-http")
                                .claim("nayon:provider", "GOOGLE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("GOOGLE_PLAY"))
                .andExpect(jsonPath("$.offers[0].productId")
                        .value("nayon.diamond.100"))
                .andExpect(jsonPath("$.offers[0].reward.amount").value(100));

        mvc.perform(post("/api/v1/store/purchases/google-play/verify")
                        .with(jwt().jwt(token -> token.subject("store-http")
                                .claim("nayon:provider", "GOOGLE")))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"nayon.diamond.100",
                                 "purchaseToken":"token-http"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("GRANTED"))
                .andExpect(jsonPath("$.reward.amount").value(100))
                .andExpect(jsonPath("$.totalAssetBalance").value(200))
                .andExpect(jsonPath("$.firstPurchaseReward.status").value("GRANTED"))
                .andExpect(jsonPath("$.firstPurchaseReward.rewards.equipment.grade")
                        .value("COMMON"))
                .andExpect(jsonPath("$.firstPurchaseReward.rewards.diamond.amount")
                        .value(50))
                .andExpect(jsonPath("$.firstPurchaseReward.rewards.gold.amount")
                        .value(10000))
                .andExpect(jsonPath("$.replay").value(false));

        mvc.perform(get("/api/v1/store/first-purchase-reward")
                        .with(jwt().jwt(token -> token.subject("store-http")
                                .claim("nayon:provider", "GOOGLE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GRANTED"))
                .andExpect(jsonPath("$.rewards.diamond.balance").value(250))
                .andExpect(jsonPath("$.rewards.gold.balance").value(10000));
    }

    private PlayerAccount bootstrappedAccount(String subject) {
        PlayerAccount account = accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.GOOGLE, subject));
        economyService.bootstrap(account.id(), UUID.randomUUID(),
                new EconomyBootstrapCommand(
                        Map.of("DIAMOND", 100L), Map.of(), List.of()));
        return account;
    }

    private GooglePlayPurchase purchased(PlayerAccount account) {
        return new GooglePlayPurchase(
                List.of("nayon.diamond.100"), GooglePlayPurchaseState.PURCHASED,
                "GPA.test-order", accountHasher.hash(account.id()),
                Instant.parse("2026-08-17T00:00:00Z"), false);
    }

    private StorePurchaseCommand command(String token) {
        return new StorePurchaseCommand("nayon.diamond.100", token);
    }

    private void configureProduct() {
        UUID productId = UUID.randomUUID();
        jdbc.update("""
                insert into store_products(
                    id, offer_id, platform, store_product_id, product_type, active)
                select ?, id, 'GOOGLE_PLAY', 'nayon.diamond.100', 'ONE_TIME', true
                  from store_offers where offer_code = 'diamond_100'
                """, productId);
        jdbc.update("""
                insert into store_product_versions(
                    id, product_id, version, reward_asset_type, reward_asset_code,
                    reward_amount, valid_from, active)
                values (?, ?, 1, 'CURRENCY', 'DIAMOND', 100,
                        '2026-01-01T00:00:00Z', true)
                """, UUID.randomUUID(), productId);
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeGooglePlayGateway googlePlayPurchaseGateway() {
            return new FakeGooglePlayGateway();
        }
    }

    static class FakeGooglePlayGateway implements GooglePlayPurchaseGateway {
        final ConcurrentLinkedQueue<Object> results = new ConcurrentLinkedQueue<>();
        volatile int getCalls;

        void enqueue(Object result) {
            results.add(result);
        }

        void clear() {
            results.clear();
            getCalls = 0;
        }

        @Override
        public GooglePlayPurchase get(String purchaseToken) {
            getCalls++;
            Object value = results.poll();
            if (value instanceof GooglePlayGatewayException exception) {
                throw exception;
            }
            if (value instanceof GooglePlayPurchase purchase) {
                return purchase;
            }
            throw new AssertionError("No fake Google result configured");
        }

    }
}
