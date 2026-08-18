package com.nayon.api.integration;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.time.KstGameTimeCalculator;
import com.nayon.api.time.ServerClock;
import com.nayon.api.weeklygift.WeeklyGiftException;
import com.nayon.api.weeklygift.WeeklyGiftRepository;
import com.nayon.api.weeklygift.WeeklyGiftService;
import com.nayon.api.weeklygift.WeeklyGiftState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "management.health.db.enabled=false")
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class WeeklyGiftPostgresTest {
    @Autowired AccountService accountService;
    @Autowired WeeklyGiftRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table player_accounts cascade");
        jdbc.execute("truncate table weekly_gift_reward_versions cascade");
    }

    @Test
    void threeKstCheckInsGrantConfiguredRewardExactlyOnce() {
        PlayerAccount account = accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.GOOGLE, "weekly-three-days"));
        bootstrap(account.id());
        configureTestReward();

        WeeklyGiftState first = serviceAt(
                "2026-08-17T03:00:00Z").checkIn(account.id());
        WeeklyGiftState second = serviceAt(
                "2026-08-18T03:00:00Z").checkIn(account.id());
        WeeklyGiftState eligible = serviceAt(
                "2026-08-19T03:00:00Z").checkIn(account.id());

        UUID requestId = UUID.randomUUID();
        WeeklyGiftState claimed = serviceAt(
                "2026-08-19T03:00:00Z").claim(account.id(), requestId);
        WeeklyGiftState replay = serviceAt(
                "2026-08-19T03:01:00Z").claim(account.id(), requestId);

        assertThat(first.loginDays()).isEqualTo(1);
        assertThat(first.claimable()).isFalse();
        assertThat(second.loginDays()).isEqualTo(2);
        assertThat(second.claimable()).isFalse();
        assertThat(eligible.loginDays()).isEqualTo(3);
        assertThat(eligible.claimable()).isTrue();
        assertThat(claimed.claimed()).isTrue();
        assertThat(claimed.reward().assetCode()).isEqualTo("DIAMOND");
        assertThat(claimed.reward().amount()).isEqualTo(1);
        assertThat(claimed.economy().currencies()).containsEntry("DIAMOND", 1L);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.economy().currencies()).containsEntry("DIAMOND", 1L);
        assertThat(jdbc.queryForObject("""
                select balance from player_wallets
                 where account_id = ? and currency_code = 'DIAMOND'
                """, Long.class, account.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from economy_ledger
                 where account_id = ? and reason_code = 'WEEKLY_GIFT'
                """, Long.class, account.id())).isEqualTo(1L);
        assertThatThrownBy(() -> serviceAt("2026-08-19T03:02:00Z")
                .claim(account.id(), UUID.randomUUID()))
                .isInstanceOfSatisfying(WeeklyGiftException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("WEEKLY_GIFT_ALREADY_CLAIMED"));
    }

    private WeeklyGiftService serviceAt(String instant) {
        var clock = new ServerClock(Clock.fixed(
                Instant.parse(instant), ZoneOffset.UTC));
        return new WeeklyGiftService(repository, new KstGameTimeCalculator(clock));
    }

    private void bootstrap(UUID accountId) {
        jdbc.update("""
                insert into economy_bootstraps(
                    account_id, request_id, request_hash, response_payload)
                values (?, ?, ?, '{}'::jsonb)
                """, accountId, UUID.randomUUID(), "a".repeat(64));
    }

    private void configureTestReward() {
        jdbc.update("""
                insert into weekly_gift_reward_versions(
                    id, version, reward_asset_type, reward_asset_code,
                    reward_amount, valid_from, active)
                values (?, 1, 'CURRENCY', 'DIAMOND', 1,
                        '2026-01-01T00:00:00Z', true)
                """, UUID.randomUUID());
    }
}
