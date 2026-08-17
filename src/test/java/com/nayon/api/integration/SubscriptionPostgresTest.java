package com.nayon.api.integration;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.store.StoreAccountHasher;
import com.nayon.api.store.google.GooglePlayGatewayException;
import com.nayon.api.subscription.PlayerSubscription;
import com.nayon.api.subscription.SubscriptionException;
import com.nayon.api.subscription.SubscriptionPlanCode;
import com.nayon.api.subscription.SubscriptionService;
import com.nayon.api.subscription.SubscriptionState;
import com.nayon.api.subscription.SubscriptionVerificationResult;
import com.nayon.api.subscription.google.GooglePlaySubscription;
import com.nayon.api.subscription.google.GooglePlaySubscriptionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

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
@Import(SubscriptionPostgresTest.FakeConfig.class)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class SubscriptionPostgresTest {

    private static final Instant START = Instant.parse("2026-08-18T00:00:00Z");
    private static final Instant EXPIRY = Instant.parse("2026-09-18T00:00:00Z");

    @Autowired AccountService accountService;
    @Autowired StoreAccountHasher accountHasher;
    @Autowired SubscriptionService service;
    @Autowired FakeGooglePlaySubscriptionGateway gateway;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table player_accounts cascade");
        jdbc.execute("truncate table subscription_benefit_versions, "
                + "store_product_versions, store_products cascade");
        gateway.clear();
        configureProduct("monthly_growth", "nayon.monthly.growth");
        configureProduct("monthly_advanced", "nayon.monthly.advanced");
    }

    @Test
    void verifiesIndependentPlansAndReplaysTokenExactly() {
        PlayerAccount account = account("subscription-independent");
        gateway.enqueue(active(account, "nayon.monthly.growth", null));
        gateway.enqueue(active(account, "nayon.monthly.advanced", null));
        UUID growthRequest = UUID.randomUUID();

        SubscriptionVerificationResult growth = service.verify(
                account.id(), growthRequest,
                "nayon.monthly.growth", "token-growth");
        SubscriptionVerificationResult sameKey = service.verify(
                account.id(), growthRequest,
                "nayon.monthly.growth", "token-growth");
        SubscriptionVerificationResult newKey = service.verify(
                account.id(), UUID.randomUUID(),
                "nayon.monthly.growth", "token-growth");
        SubscriptionVerificationResult advanced = service.verify(
                account.id(), UUID.randomUUID(),
                "nayon.monthly.advanced", "token-advanced");

        assertThat(growth.subscription().planCode())
                .isEqualTo(SubscriptionPlanCode.MONTHLY_GROWTH);
        assertThat(advanced.subscription().planCode())
                .isEqualTo(SubscriptionPlanCode.MONTHLY_ADVANCED);
        assertThat(sameKey.replay()).isTrue();
        assertThat(newKey.replay()).isTrue();
        assertThat(gateway.calls).isEqualTo(2);
        assertThat(service.findAll(account.id())).hasSize(2);
    }

    @Test
    void ambiguousReplacementPreservesOldEntitlementUntilRetrySucceeds() {
        PlayerAccount account = account("subscription-replacement");
        gateway.enqueue(active(account, "nayon.monthly.growth", null));
        service.verify(account.id(), UUID.randomUUID(),
                "nayon.monthly.growth", "token-old");
        gateway.enqueue(new GooglePlayGatewayException(
                "GOOGLE_PLAY_UNAVAILABLE", true, "temporary"));
        UUID replacementRequest = UUID.randomUUID();

        assertThatThrownBy(() -> service.verify(
                account.id(), replacementRequest,
                "nayon.monthly.growth", "token-new"))
                .isInstanceOfSatisfying(SubscriptionException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("GOOGLE_PLAY_UNAVAILABLE"));
        PlayerSubscription preserved = service.findAll(account.id()).getFirst();
        assertThat(preserved.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(preserved.entitled(START.plusSeconds(60))).isTrue();
        assertThat(jdbc.queryForObject("""
                select purchase_token from player_subscriptions where account_id = ?
                """, String.class, account.id())).isEqualTo("token-old");

        gateway.enqueue(active(account, "nayon.monthly.growth", "token-old"));
        SubscriptionVerificationResult retried = service.verify(
                account.id(), replacementRequest,
                "nayon.monthly.growth", "token-new");
        assertThat(retried.subscription().state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(jdbc.queryForObject("""
                select purchase_token from player_subscriptions where account_id = ?
                """, String.class, account.id())).isEqualTo("token-new");
    }

    @Test
    void purchaseTokenCannotMoveAcrossAccounts() {
        PlayerAccount owner = account("subscription-owner");
        PlayerAccount attacker = account("subscription-attacker");
        gateway.enqueue(active(owner, "nayon.monthly.growth", null));
        service.verify(owner.id(), UUID.randomUUID(),
                "nayon.monthly.growth", "token-owned");

        assertThatThrownBy(() -> service.verify(
                attacker.id(), UUID.randomUUID(),
                "nayon.monthly.growth", "token-owned"))
                .isInstanceOfSatisfying(SubscriptionException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("SUBSCRIPTION_PURCHASE_TOKEN_CONFLICT"));
        assertThat(service.findAll(attacker.id())).isEmpty();
    }

    @Test
    void authenticatedCatalogAndVerifyMatchPublishedContract() throws Exception {
        PlayerAccount account = account("subscription-http");
        gateway.enqueue(active(account, "nayon.monthly.growth", null));

        mvc.perform(get("/api/v1/subscriptions/catalog")
                        .param("platform", "GOOGLE_PLAY")
                        .with(jwtFor("subscription-http")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.length()").value(2))
                .andExpect(jsonPath("$.plans[0].productType")
                        .value("SUBSCRIPTION"));

        mvc.perform(post("/api/v1/store/subscriptions/google-play/verify")
                        .with(jwtFor("subscription-http"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"nayon.monthly.growth",
                                 "purchaseToken":"token-http"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subscription.planCode")
                        .value("MONTHLY_GROWTH"))
                .andExpect(jsonPath("$.subscription.entitled").value(true))
                .andExpect(jsonPath("$.replay").value(false));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(
            String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("nayon:provider", "GOOGLE"));
    }

    private PlayerAccount account(String subject) {
        return accountService.resolveOrCreate(new AuthenticatedIdentity(
                AuthProvider.GOOGLE, subject));
    }

    private GooglePlaySubscription active(
            PlayerAccount account, String productId, String linkedToken) {
        return new GooglePlaySubscription(
                productId, SubscriptionState.ACTIVE, "GPA.test",
                accountHasher.hash(account.id()), START, EXPIRY,
                true, true, linkedToken);
    }

    private void configureProduct(String offerCode, String productId) {
        UUID productUuid = UUID.randomUUID();
        jdbc.update("""
                insert into store_products(
                    id, offer_id, platform, store_product_id,
                    product_type, active)
                select ?, id, 'GOOGLE_PLAY', ?, 'SUBSCRIPTION', true
                  from store_offers where offer_code = ?
                """, productUuid, productId, offerCode);
        jdbc.update("""
                insert into store_product_versions(
                    id, product_id, version, fulfillment_type,
                    valid_from, active)
                values (?, ?, 1, 'SUBSCRIPTION', '2026-01-01T00:00:00Z', true)
                """, UUID.randomUUID(), productUuid);
        jdbc.update("""
                insert into subscription_benefit_versions(
                    id, plan_id, version, benefit_code, benefit_value,
                    valid_from, active)
                select ?, id, 1, 'MAX_ENERGY', 10,
                       '2026-01-01T00:00:00Z', true
                  from subscription_plans where offer_id = (
                    select id from store_offers where offer_code = ?)
                """, UUID.randomUUID(), offerCode);
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeGooglePlaySubscriptionGateway fakeGooglePlaySubscriptionGateway() {
            return new FakeGooglePlaySubscriptionGateway();
        }
    }

    static final class FakeGooglePlaySubscriptionGateway
            implements GooglePlaySubscriptionGateway {
        private final Queue<Object> responses = new ConcurrentLinkedQueue<>();
        private int calls;

        void enqueue(Object response) {
            responses.add(response);
        }

        void clear() {
            responses.clear();
            calls = 0;
        }

        @Override
        public synchronized GooglePlaySubscription get(String purchaseToken) {
            calls++;
            Object response = responses.remove();
            if (response instanceof RuntimeException exception) {
                throw exception;
            }
            return (GooglePlaySubscription) response;
        }
    }
}
