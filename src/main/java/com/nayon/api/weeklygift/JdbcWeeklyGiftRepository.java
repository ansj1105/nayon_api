package com.nayon.api.weeklygift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.economy.EconomyCreditResult;
import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import com.nayon.api.time.KstGameTimeCalculator;
import com.nayon.api.time.RewardPeriod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcWeeklyGiftRepository implements WeeklyGiftRepository {
    private final JdbcTemplate jdbc;
    private final EconomyRepository economy;
    private final ObjectMapper objectMapper;

    public JdbcWeeklyGiftRepository(
            JdbcTemplate jdbc,
            EconomyRepository economy,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.economy = economy;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public WeeklyGiftState get(
            UUID accountId, RewardPeriod period, Instant now) {
        return currentState(accountId, period, now, false);
    }

    @Override
    @Transactional
    public WeeklyGiftState checkIn(
            UUID accountId,
            RewardPeriod period,
            LocalDate loginDate,
            Instant now) {
        lock(accountId);
        jdbc.update("""
                insert into player_weekly_gift_weeks(account_id, week_start)
                values (?, ?)
                on conflict (account_id, week_start) do nothing
                """, accountId, java.sql.Date.valueOf(period.periodKey()));
        jdbc.update("""
                insert into player_weekly_gift_login_days(
                    account_id, week_start, login_date, first_seen_at)
                values (?, ?, ?, ?)
                on conflict (account_id, week_start, login_date) do nothing
                """, accountId, java.sql.Date.valueOf(period.periodKey()),
                java.sql.Date.valueOf(loginDate), timestamp(now));
        return currentState(accountId, period, now, false);
    }

    @Override
    @Transactional
    public WeeklyGiftState claim(
            UUID accountId,
            UUID requestId,
            RewardPeriod period,
            Instant now) {
        lock(accountId);
        List<StoredClaim> byRequest = jdbc.query("""
                select account_id, week_start, claim_response::text
                  from player_weekly_gift_weeks
                 where claim_request_id = ?
                """, (rs, rowNumber) -> new StoredClaim(
                rs.getObject("account_id", UUID.class),
                rs.getObject("week_start", LocalDate.class),
                rs.getString("claim_response")), requestId);
        if (!byRequest.isEmpty()) {
            StoredClaim stored = byRequest.getFirst();
            if (!stored.accountId().equals(accountId)
                    || !stored.weekStart().equals(period.periodKey())) {
                throw new WeeklyGiftException(
                        "WEEKLY_GIFT_IDEMPOTENCY_CONFLICT",
                        "Idempotency key was already used for another weekly gift.");
            }
            return decode(stored.response()).asReplay();
        }

        List<Boolean> week = jdbc.query("""
                select claimed_at is not null as claimed
                  from player_weekly_gift_weeks
                 where account_id = ? and week_start = ?
                 for update
                """, (rs, rowNumber) -> rs.getBoolean("claimed"),
                accountId, java.sql.Date.valueOf(period.periodKey()));
        int loginDays = loginDays(accountId, period.periodKey());
        if (loginDays < 3) {
            throw new WeeklyGiftException(
                    "WEEKLY_GIFT_NOT_ELIGIBLE",
                    "Three distinct KST login dates are required.");
        }
        if (!week.isEmpty() && week.getFirst()) {
            throw new WeeklyGiftException(
                    "WEEKLY_GIFT_ALREADY_CLAIMED",
                    "The weekly gift was already claimed.");
        }

        RewardVersion version = activeReward(now);
        if (version == null) {
            throw new WeeklyGiftException(
                    "WEEKLY_GIFT_REWARD_NOT_CONFIGURED",
                    "The weekly gift reward is not configured.");
        }
        EconomySnapshot before = economy.findSnapshot(accountId);
        if (!before.bootstrapped()) {
            throw new WeeklyGiftException(
                    "ECONOMY_NOT_BOOTSTRAPPED",
                    "Account economy must be bootstrapped before reward grant.");
        }

        EconomyCreditResult credit = credit(
                accountId, requestId, version.reward(), requestId);
        WeeklyGiftState result = WeeklyGiftState.create(
                now.atZone(KstGameTimeCalculator.KST), period, loginDays,
                true, version.reward(), credit.economy(), false);
        jdbc.update("""
                update player_weekly_gift_weeks
                   set claimed_at = ?, claim_request_id = ?, reward_version_id = ?,
                       claim_response = ?::jsonb, updated_at = ?
                 where account_id = ? and week_start = ?
                """, timestamp(now), requestId, version.id(), encode(result),
                timestamp(now), accountId, java.sql.Date.valueOf(period.periodKey()));
        return result;
    }

    private WeeklyGiftState currentState(
            UUID accountId, RewardPeriod period, Instant now, boolean replay) {
        List<ClaimedRow> rows = jdbc.query("""
                select w.claimed_at is not null as claimed,
                       r.reward_asset_type, r.reward_asset_code, r.reward_amount
                  from player_weekly_gift_weeks w
                  left join weekly_gift_reward_versions r
                    on r.id = w.reward_version_id
                 where w.account_id = ? and w.week_start = ?
                """, (rs, rowNumber) -> new ClaimedRow(
                rs.getBoolean("claimed"),
                rs.getString("reward_asset_type") == null ? null
                        : new WeeklyGiftReward(
                        rs.getString("reward_asset_type"),
                        rs.getString("reward_asset_code"),
                        rs.getLong("reward_amount"))),
                accountId, java.sql.Date.valueOf(period.periodKey()));
        boolean claimed = !rows.isEmpty() && rows.getFirst().claimed();
        WeeklyGiftReward reward = claimed
                ? rows.getFirst().reward()
                : reward(activeReward(now));
        EconomySnapshot snapshot = claimed ? economy.findSnapshot(accountId) : null;
        return WeeklyGiftState.create(
                now.atZone(KstGameTimeCalculator.KST), period,
                loginDays(accountId, period.periodKey()), claimed,
                reward, snapshot, replay);
    }

    private int loginDays(UUID accountId, LocalDate weekStart) {
        Integer count = jdbc.queryForObject("""
                select count(*) from player_weekly_gift_login_days
                 where account_id = ? and week_start = ?
                """, Integer.class, accountId, java.sql.Date.valueOf(weekStart));
        return count == null ? 0 : count;
    }

    private RewardVersion activeReward(Instant now) {
        List<RewardVersion> versions = jdbc.query("""
                select id, reward_asset_type, reward_asset_code, reward_amount
                  from weekly_gift_reward_versions
                 where active and valid_from <= ?
                   and (valid_until is null or valid_until > ?)
                 order by version desc
                 limit 1
                """, (rs, rowNumber) -> new RewardVersion(
                rs.getObject("id", UUID.class),
                new WeeklyGiftReward(
                        rs.getString("reward_asset_type"),
                        rs.getString("reward_asset_code"),
                        rs.getLong("reward_amount"))), timestamp(now), timestamp(now));
        return versions.isEmpty() ? null : versions.getFirst();
    }

    private EconomyCreditResult credit(
            UUID accountId,
            UUID requestId,
            WeeklyGiftReward reward,
            UUID referenceId) {
        return switch (reward.assetType()) {
            case "CURRENCY" -> economy.creditCurrencyWithLedger(
                    accountId, requestId, reward.assetCode(), reward.amount(),
                    "WEEKLY_GIFT", "WEEKLY_GIFT_CLAIM", referenceId);
            case "ITEM" -> economy.creditItemWithLedger(
                    accountId, requestId, reward.assetCode(), reward.amount(),
                    "WEEKLY_GIFT", "WEEKLY_GIFT_CLAIM", referenceId);
            default -> throw new WeeklyGiftException(
                    "WEEKLY_GIFT_REWARD_NOT_CONFIGURED",
                    "The configured weekly gift asset type is unsupported.");
        };
    }

    private WeeklyGiftReward reward(RewardVersion version) {
        return version == null ? null : version.reward();
    }

    private String encode(WeeklyGiftState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize weekly gift response", exception);
        }
    }

    private WeeklyGiftState decode(String json) {
        try {
            return objectMapper.readValue(json, WeeklyGiftState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot deserialize weekly gift response", exception);
        }
    }

    private void lock(UUID accountId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> null, "weekly-gift-account:" + accountId);
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return java.sql.Timestamp.from(value);
    }

    private record RewardVersion(UUID id, WeeklyGiftReward reward) {
    }

    private record ClaimedRow(boolean claimed, WeeklyGiftReward reward) {
    }

    private record StoredClaim(UUID accountId, LocalDate weekStart, String response) {
    }
}
