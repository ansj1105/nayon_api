package com.nayon.api.integration;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.subscription.SubscriptionDailyRewardResult;
import com.nayon.api.subscription.SubscriptionException;
import com.nayon.api.subscription.SubscriptionPlanCode;
import com.nayon.api.subscription.SubscriptionRewardGrant;
import com.nayon.api.subscription.SubscriptionRewardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        properties = {
                "management.health.db.enabled=false",
                "nayon.store.account-hash-key=test-only-account-hash-key"
        })
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class SubscriptionRewardPostgresTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Autowired AccountService accountService;
    @Autowired SubscriptionRewardRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table player_accounts cascade");
        jdbc.execute("truncate table subscription_benefit_versions cascade");
    }

    @Test
    void initialIsLifetimeOnceAndDailyIsOncePerUtcDate() {
        PlayerAccount account = account("subscription-reward");
        bootstrap(account.id());
        UUID subscriptionId = subscribe(
                account.id(), SubscriptionPlanCode.MONTHLY_GROWTH, "token-reward");
        benefit(SubscriptionPlanCode.MONTHLY_GROWTH,
                "INITIAL_DIAMOND", 500);
        benefit(SubscriptionPlanCode.MONTHLY_GROWTH,
                "DAILY_DIAMOND", 50);

        SubscriptionRewardGrant initial = repository.grantInitialIfEligible(
                account.id(), subscriptionId, UUID.randomUUID(), NOW);
        SubscriptionRewardGrant initialReplay = repository.grantInitialIfEligible(
                account.id(), subscriptionId, UUID.randomUUID(), NOW.plusSeconds(60));
        SubscriptionDailyRewardResult first = repository.claimDaily(
                account.id(), UUID.randomUUID(), hash("growth-day-1"),
                SubscriptionPlanCode.MONTHLY_GROWTH,
                LocalDate.parse("2026-08-18"), NOW);
        SubscriptionDailyRewardResult replay = repository.claimDaily(
                account.id(), UUID.randomUUID(), hash("growth-day-1-retry"),
                SubscriptionPlanCode.MONTHLY_GROWTH,
                LocalDate.parse("2026-08-18"), NOW.plusSeconds(60));
        SubscriptionDailyRewardResult nextDay = repository.claimDaily(
                account.id(), UUID.randomUUID(), hash("growth-day-2"),
                SubscriptionPlanCode.MONTHLY_GROWTH,
                LocalDate.parse("2026-08-19"), NOW.plusSeconds(86_400));

        assertThat(initial.amount()).isEqualTo(500L);
        assertThat(initialReplay.amount()).isEqualTo(500L);
        assertThat(replay.grantId()).isEqualTo(first.grantId());
        assertThat(replay.replay()).isTrue();
        assertThat(nextDay.grantId()).isNotEqualTo(first.grantId());
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger where account_id = ?
                  and reason_code = 'SUBSCRIPTION_INITIAL_REWARD'
                """, Long.class, account.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger where account_id = ?
                  and reason_code = 'SUBSCRIPTION_DAILY_REWARD'
                """, Long.class, account.id())).isEqualTo(2L);
    }

    @Test
    void advancedDailyRewardRequiresItsOwnSubscription() {
        PlayerAccount account = account("subscription-daily-independent");
        bootstrap(account.id());
        subscribe(account.id(), SubscriptionPlanCode.MONTHLY_GROWTH, "token-growth");
        benefit(SubscriptionPlanCode.MONTHLY_ADVANCED,
                "DAILY_DIAMOND", 150);

        assertThatThrownBy(() -> repository.claimDaily(
                account.id(), UUID.randomUUID(), hash("advanced"),
                SubscriptionPlanCode.MONTHLY_ADVANCED,
                LocalDate.parse("2026-08-18"), NOW))
                .isInstanceOfSatisfying(SubscriptionException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("SUBSCRIPTION_REQUIRED"));
    }

    private PlayerAccount account(String subject) {
        return accountService.resolveOrCreate(new AuthenticatedIdentity(
                AuthProvider.GOOGLE, subject));
    }

    private void bootstrap(UUID accountId) {
        jdbc.update("""
                insert into economy_bootstraps(
                    account_id, request_id, request_hash, response_payload)
                values (?, ?, ?, '{}'::jsonb)
                """, accountId, UUID.randomUUID(), "a".repeat(64));
    }

    private UUID subscribe(
            UUID accountId, SubscriptionPlanCode planCode, String token) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into player_subscriptions(
                    id, account_id, plan_id, purchase_token,
                    purchase_token_hash, state, started_at, expires_at,
                    auto_renewing, acknowledgement_state, last_verified_at)
                select ?, ?, id, ?, ?, 'ACTIVE',
                       '2026-08-01T00:00:00Z', '2027-08-01T00:00:00Z',
                       true, 'ACKNOWLEDGED', now()
                  from subscription_plans where plan_code = ?
                """, id, accountId, token, hash(token), planCode.name());
        return id;
    }

    private void benefit(
            SubscriptionPlanCode planCode, String code, long value) {
        jdbc.update("""
                insert into subscription_benefit_versions(
                    id, plan_id, version, benefit_code, benefit_value,
                    valid_from, active)
                select ?, id, 1, ?, ?, '2026-01-01T00:00:00Z', true
                  from subscription_plans where plan_code = ?
                """, UUID.randomUUID(), code, value, planCode.name());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
