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
import com.nayon.api.limitedbenefit.admob.AdMobRewardCallbackResult;
import com.nayon.api.limitedbenefit.admob.AdMobRewardService;
import com.nayon.api.limitedbenefit.admob.AdMobSsvCallback;
import com.nayon.api.limitedbenefit.admob.LimitedBenefitAdSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
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
    @Autowired AdMobRewardService adMobRewardService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table player_limited_benefit_claims, admob_reward_callbacks, limited_benefit_ad_sessions, player_first_purchase_rewards, store_purchase_receipts, store_product_versions, store_products, player_account_link_rewards, player_korion_wallet_links, korion_wallet_link_requests, player_share_rewards, player_settings, "
                + "offline_battle_decisions, offline_battle_runs, offline_battle_submissions, "
                + "offline_play_window_requests, offline_play_budgets, battle_rewards, "
                + "battle_anomalies, battle_completions, battle_sessions, player_progression, "
                + "gacha_draw_results, gacha_draws, gacha_pity_states, economy_bootstraps, "
                + "economy_ledger, player_equipment, player_items, player_wallets, save_imports, "
                + "player_save_states, auth_identities, player_accounts cascade");
        jdbc.update("""
                update limited_benefit_offers
                   set fulfillment_type = 'GOOGLE_PLAY',
                       store_offer_id = '00000000-0000-0000-0000-000000009201',
                       provider_key = null
                 where offer_code = 'paid_3000_a'
                """);
        jdbc.update("""
                update limited_benefit_offer_rewards
                   set reward_type = 'ITEM', reward_code = 'SILVER_KEY', amount = 8
                 where offer_id = (select id from limited_benefit_offers
                                    where offer_code = 'paid_3000_a')
                   and reward_order = 1
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

    @Test
    void googleReceiptClaimsOneDailyBundleAndCannotCrossAccounts() {
        PlayerAccount first = bootstrappedAccount("limited-google-first");
        PlayerAccount second = bootstrappedAccount("limited-google-second");
        UUID receiptId = insertLimitedReceipt(
                first.id(), "limited_paid_3000_a", Instant.now());
        jdbc.update("""
                update limited_benefit_offer_rewards
                   set reward_type = 'EQUIPMENT_BOX',
                       reward_code = 'ADVANCED_BOX', amount = 1
                 where offer_id = (select id from limited_benefit_offers
                                    where offer_code = 'paid_3000_a')
                   and reward_order = 1
                """);
        UUID requestId = UUID.randomUUID();

        LimitedBenefitClaimResult claimed = service.claim(
                first.id(), requestId, "paid_3000_a", receiptId, null);
        LimitedBenefitClaimResult replay = service.claim(
                first.id(), requestId, "paid_3000_a", receiptId, null);

        assertThat(claimed.replay()).isFalse();
        assertThat(replay.replay()).isTrue();
        assertThat(claimed.economy().currencies()).containsEntry("DIAMOND", 260L);
        assertThat(claimed.economy().items())
                .containsEntry("ADVANCED_BOX", 1L)
                .containsEntry("RANDOM_SCROLL", 1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from player_limited_benefit_claims
                 where receipt_id = ? and proof_type = 'GOOGLE_PLAY'
                """, Long.class, receiptId)).isEqualTo(1L);
        assertThatThrownBy(() -> service.claim(
                second.id(), UUID.randomUUID(), "paid_3000_a", receiptId, null))
                .isInstanceOfSatisfying(LimitedBenefitException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("LIMITED_BENEFIT_PROOF_INVALID"));
    }

    @Test
    void googleReceiptMustMatchOfferAndCurrentKstCycle() {
        PlayerAccount wrongOffer = bootstrappedAccount("limited-google-offer");
        insertLimitedReceipt(
                wrongOffer.id(), "limited_paid_3000_a", Instant.now());
        UUID wrongOfferReceipt = insertLimitedReceipt(
                wrongOffer.id(), "limited_paid_7000_a", Instant.now());
        assertThatThrownBy(() -> service.claim(
                wrongOffer.id(), UUID.randomUUID(), "paid_3000_a",
                wrongOfferReceipt, null))
                .isInstanceOfSatisfying(LimitedBenefitException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("LIMITED_BENEFIT_PROOF_INVALID"));

        PlayerAccount oldReceipt = bootstrappedAccount("limited-google-old");
        UUID oldReceiptId = insertLimitedReceipt(
                oldReceipt.id(), "limited_paid_3000_a",
                Instant.now().minusSeconds(48 * 60 * 60));
        assertThatThrownBy(() -> service.claim(
                oldReceipt.id(), UUID.randomUUID(), "paid_3000_a",
                oldReceiptId, null))
                .isInstanceOfSatisfying(LimitedBenefitException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("LIMITED_BENEFIT_PROOF_INVALID"));
    }

    @Test
    void verifiedAdMobSessionClaimsOnceAndDuplicateCallbackReplays() {
        makeFirstOfferAdMobForTest();
        PlayerAccount account = bootstrappedAccount("limited-admob");
        LimitedBenefitAdSession session = adMobRewardService.createSession(
                account.id(), "paid_3000_a");
        AdMobSsvCallback callback = callback(session, account.id(), "admob-tx-1");

        AdMobRewardCallbackResult first = adMobRewardService.acceptVerified(callback);
        AdMobRewardCallbackResult replay = adMobRewardService.acceptVerified(callback);
        LimitedBenefitAdSession recovered = adMobRewardService.createSession(
                account.id(), "paid_3000_a");

        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'LIMITED_BENEFIT'
                """, Long.class, account.id())).isZero();
        LimitedBenefitClaimResult claimed = service.claim(
                account.id(), UUID.randomUUID(), "paid_3000_a",
                null, session.sessionId());

        assertThat(first.replay()).isFalse();
        assertThat(replay.replay()).isTrue();
        assertThat(recovered.sessionId()).isEqualTo(session.sessionId());
        assertThat(recovered.status()).isEqualTo("VERIFIED");
        assertThat(claimed.economy().currencies()).containsEntry("DIAMOND", 260L);
        assertThat(jdbc.queryForObject("""
                select status from limited_benefit_ad_sessions where id = ?
                """, String.class, session.sessionId())).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("""
                select count(*) from admob_reward_callbacks where transaction_id = ?
                """, Long.class, callback.transactionId())).isEqualTo(1L);
    }

    @Test
    void adMobCallbackRejectsCrossAccountAndExpiredSession() {
        makeFirstOfferAdMobForTest();
        PlayerAccount account = bootstrappedAccount("limited-admob-owner");
        PlayerAccount other = bootstrappedAccount("limited-admob-other");
        LimitedBenefitAdSession session = adMobRewardService.createSession(
                account.id(), "paid_3000_a");

        assertThatThrownBy(() -> adMobRewardService.acceptVerified(
                callback(session, other.id(), "admob-tx-owner")))
                .isInstanceOf(LimitedBenefitException.class);

        jdbc.update("update limited_benefit_ad_sessions set created_at = now() - interval '20 minutes', expires_at = now() - interval '1 second' where id = ?",
                session.sessionId());
        assertThatThrownBy(() -> adMobRewardService.acceptVerified(
                callback(session, account.id(), "admob-tx-expired")))
                .isInstanceOf(LimitedBenefitException.class);
    }

    @Test
    void adMobCallbackRejectsRewardTimestampOutsideSessionWindow() {
        makeFirstOfferAdMobForTest();
        PlayerAccount account = bootstrappedAccount("limited-admob-time");
        LimitedBenefitAdSession session = adMobRewardService.createSession(
                account.id(), "paid_3000_a");

        assertThatThrownBy(() -> adMobRewardService.acceptVerified(
                callback(session, account.id(), "admob-tx-future",
                        session.expiresAt().plusSeconds(1))))
                .isInstanceOf(LimitedBenefitException.class);
        assertThatThrownBy(() -> adMobRewardService.acceptVerified(
                callback(session, account.id(), "admob-tx-before",
                        Instant.now().minusSeconds(60 * 60))))
                .isInstanceOf(LimitedBenefitException.class);
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

    private void makeFirstOfferAdMobForTest() {
        jdbc.update("""
                update limited_benefit_offers
                   set fulfillment_type = 'ADMOB_SSV', store_offer_id = null,
                       provider_key = 'test-ad-unit'
                 where offer_code = 'paid_3000_a'
                """);
    }

    private AdMobSsvCallback callback(
            LimitedBenefitAdSession session, UUID accountId, String transactionId) {
        return callback(session, accountId, transactionId, Instant.now());
    }

    private AdMobSsvCallback callback(
            LimitedBenefitAdSession session, UUID accountId,
            String transactionId, Instant rewardedAt) {
        return new AdMobSsvCallback(
                "test-raw-" + transactionId, 123L, session.adUnitId(),
                session.sessionId(), 1L, "nayon_limited_benefit",
                rewardedAt, transactionId, accountId);
    }

    private UUID insertLimitedReceipt(
            UUID accountId, String storeOfferCode, Instant purchaseTime) {
        UUID productId = jdbc.query("""
                select p.id from store_products p
                  join store_offers o on o.id = p.offer_id
                 where o.offer_code = ? and p.platform = 'GOOGLE_PLAY'
                """, (rs, row) -> rs.getObject(1, UUID.class), storeOfferCode)
                .stream().findFirst().orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    jdbc.update("""
                            insert into store_products(
                                id, offer_id, platform, store_product_id,
                                product_type, active)
                            select ?, id, 'GOOGLE_PLAY', ?, 'ONE_TIME', true
                              from store_offers where offer_code = ?
                            """, id, "nayon." + storeOfferCode, storeOfferCode);
                    jdbc.update("""
                            insert into store_product_versions(
                                id, product_id, version, fulfillment_type,
                                reward_asset_type, reward_asset_code, reward_amount,
                                valid_from, active)
                            values (?, ?, 1, 'LIMITED_BENEFIT', null, null, null,
                                    '2026-01-01T00:00:00Z', true)
                            """, UUID.randomUUID(), id);
                    return id;
                });
        UUID versionId = jdbc.queryForObject(
                "select id from store_product_versions where product_id = ?",
                UUID.class, productId);
        UUID receiptId = UUID.randomUUID();
        String token = "token-" + receiptId;
        String tokenHash = receiptId.toString().replace("-", "") + "0".repeat(32);
        jdbc.update("""
                insert into store_purchase_receipts(
                    id, account_id, request_id, request_hash, platform,
                    store_product_id, purchase_token, purchase_token_hash,
                    state, product_id, product_version_id, fulfillment_type,
                    google_order_id, google_purchase_time, verified_at,
                    granted_at)
                values (?, ?, ?, repeat('a', 64), 'GOOGLE_PLAY', ?, ?, ?,
                        'GRANTED', ?, ?, 'LIMITED_BENEFIT', ?, ?, now(), now())
                """, receiptId, accountId, UUID.randomUUID(),
                "nayon." + storeOfferCode, token, tokenHash,
                productId, versionId, "GPA." + receiptId,
                java.sql.Timestamp.from(purchaseTime));
        return receiptId;
    }
}
