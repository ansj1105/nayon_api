package com.nayon.api.integration;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.levelreward.LevelRewardClaimResult;
import com.nayon.api.levelreward.LevelRewardException;
import com.nayon.api.levelreward.LevelRewardService;
import com.nayon.api.levelreward.LevelRewardTrackCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        properties = {
                "management.health.db.enabled=false",
                "nayon.store.account-hash-key=test-only-account-hash-key"
        })
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class LevelRewardPostgresTest {

    @Autowired AccountService accountService;
    @Autowired LevelRewardService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table player_accounts cascade");
        jdbc.execute("truncate table level_reward_versions cascade");
    }

    @Test
    void lifetimeClaimDoesNotResetAndPaidTracksStayIndependent() {
        PlayerAccount account = account("level-lifetime");
        bootstrap(account.id(), 1_450L);
        reward(1, "FREE", 5, "DIAMOND", 30);
        reward(1, "PREMIUM", 5, "DIAMOND", 100);
        reward(1, "ROYAL", 5, "DIAMOND", 300);
        subscribe(account.id(), "MONTHLY_GROWTH", "token-growth");

        LevelRewardClaimResult free = service.claim(
                account.id(), UUID.randomUUID(), LevelRewardTrackCode.FREE, 5);
        LevelRewardClaimResult freeReplay = service.claim(
                account.id(), UUID.randomUUID(), LevelRewardTrackCode.FREE, 5);
        LevelRewardClaimResult premium = service.claim(
                account.id(), UUID.randomUUID(), LevelRewardTrackCode.PREMIUM, 5);

        assertThat(free.replay()).isFalse();
        assertThat(freeReplay.replay()).isTrue();
        assertThat(freeReplay.claimId()).isEqualTo(free.claimId());
        assertThat(premium.reward().amount()).isEqualTo(100L);
        assertThatThrownBy(() -> service.claim(
                account.id(), UUID.randomUUID(), LevelRewardTrackCode.ROYAL, 5))
                .isInstanceOfSatisfying(LevelRewardException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("LEVEL_REWARD_SUBSCRIPTION_REQUIRED"));
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'LEVEL_REWARD'
                """, Long.class, account.id())).isEqualTo(2L);

        jdbc.update("update level_reward_versions set active = false");
        reward(2, "FREE", 5, "DIAMOND", 999);
        LevelRewardClaimResult afterCatalogChange = service.claim(
                account.id(), UUID.randomUUID(), LevelRewardTrackCode.FREE, 5);
        assertThat(afterCatalogChange.claimId()).isEqualTo(free.claimId());
        assertThat(afterCatalogChange.reward().amount()).isEqualTo(30L);
    }

    @Test
    void concurrentClaimsCreateOneLedgerCredit() throws Exception {
        PlayerAccount account = account("level-concurrent");
        bootstrap(account.id(), 1_450L);
        reward(1, "FREE", 5, "DIAMOND", 30);
        Callable<LevelRewardClaimResult> first = () -> service.claim(
                account.id(), UUID.randomUUID(), LevelRewardTrackCode.FREE, 5);
        Callable<LevelRewardClaimResult> second = () -> service.claim(
                account.id(), UUID.randomUUID(), LevelRewardTrackCode.FREE, 5);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<LevelRewardClaimResult> results = executor.invokeAll(
                    List.of(first, second)).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
            assertThat(results).extracting(LevelRewardClaimResult::claimId)
                    .containsOnly(results.getFirst().claimId());
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'LEVEL_REWARD'
                """, Long.class, account.id())).isEqualTo(1L);
    }

    private PlayerAccount account(String subject) {
        return accountService.resolveOrCreate(new AuthenticatedIdentity(
                AuthProvider.GOOGLE, subject));
    }

    private void bootstrap(UUID accountId, long accountExp) {
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                insert into economy_bootstraps(
                    account_id, request_id, request_hash, response_payload)
                values (?, ?, ?, '{}'::jsonb)
                """, accountId, requestId, "a".repeat(64));
        jdbc.update("""
                insert into player_progression(account_id, account_exp)
                values (?, ?)
                """, accountId, accountExp);
    }

    private void reward(
            int version, String track, int level,
            String assetCode, long amount) {
        jdbc.update("""
                insert into level_reward_versions(
                    id, catalog_version, track_code, required_level,
                    reward_asset_type, reward_asset_code, reward_amount,
                    valid_from, active)
                values (?, ?, ?, ?, 'CURRENCY', ?, ?,
                        '2026-01-01T00:00:00Z', true)
                """, UUID.randomUUID(), version, track, level, assetCode, amount);
    }

    private void subscribe(UUID accountId, String planCode, String token) {
        jdbc.update("""
                insert into player_subscriptions(
                    id, account_id, plan_id, purchase_token,
                    purchase_token_hash, state, started_at, expires_at,
                    auto_renewing, acknowledgement_state, last_verified_at)
                select ?, ?, id, ?, ?, 'ACTIVE',
                       '2026-08-01T00:00:00Z', '2027-08-01T00:00:00Z',
                       true, 'ACKNOWLEDGED', now()
                  from subscription_plans where plan_code = ?
                """, UUID.randomUUID(), accountId, token,
                Integer.toHexString(token.hashCode()).repeat(16).substring(0, 64),
                planCode);
    }
}
