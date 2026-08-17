package com.nayon.api.subscription;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.economy.EconomyCreditResult;
import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcSubscriptionRewardRepository
        implements SubscriptionRewardRepository {

    private final JdbcTemplate jdbc;
    private final EconomyRepository economyRepository;
    private final ObjectMapper objectMapper;

    public JdbcSubscriptionRewardRepository(
            JdbcTemplate jdbc,
            EconomyRepository economyRepository,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.economyRepository = economyRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public SubscriptionRewardGrant grantInitialIfEligible(
            UUID accountId,
            UUID subscriptionId,
            UUID requestId,
            Instant now) {
        List<InitialRow> existing = jdbc.query("""
                select reward_asset_code, reward_amount
                  from player_subscription_initial_rewards
                 where account_id = ? and plan_id = (
                    select plan_id from player_subscriptions where id = ?)
                """, (rs, rowNumber) -> new InitialRow(
                rs.getString("reward_asset_code"),
                rs.getLong("reward_amount")), accountId, subscriptionId);
        if (!existing.isEmpty()) {
            EconomySnapshot economy = economyRepository.findSnapshot(accountId);
            InitialRow reward = existing.getFirst();
            return new SubscriptionRewardGrant(
                    reward.assetCode(), reward.amount(),
                    economy.currencies().getOrDefault(reward.assetCode(), 0L));
        }
        List<BenefitRow> benefits = jdbc.query("""
                select b.id, b.plan_id, b.benefit_value
                  from player_subscriptions ps
                  join subscription_benefit_versions b on b.plan_id = ps.plan_id
                 where ps.id = ? and ps.account_id = ?
                   and ps.state in ('ACTIVE', 'CANCELED', 'GRACE_PERIOD')
                   and ps.expires_at > ?
                   and b.benefit_code = 'INITIAL_DIAMOND'
                   and b.active and b.valid_from <= ?
                   and (b.valid_until is null or b.valid_until > ?)
                """, (rs, rowNumber) -> new BenefitRow(
                rs.getObject("id", UUID.class),
                rs.getObject("plan_id", UUID.class),
                rs.getLong("benefit_value")), subscriptionId, accountId,
                timestamp(now), timestamp(now), timestamp(now));
        if (benefits.isEmpty()) {
            return null;
        }
        requireBootstrapped(accountId);
        BenefitRow benefit = benefits.getFirst();
        UUID grantId = UUID.randomUUID();
        EconomyCreditResult credit = economyRepository.creditCurrencyWithLedger(
                accountId, requestId, "DIAMOND", benefit.value(),
                "SUBSCRIPTION_INITIAL_REWARD",
                "SUBSCRIPTION_INITIAL_REWARD", grantId);
        jdbc.update("""
                insert into player_subscription_initial_rewards(
                    id, account_id, plan_id, benefit_version_id,
                    reward_asset_code, reward_amount, ledger_id, granted_at)
                values (?, ?, ?, ?, 'DIAMOND', ?, ?, ?)
                """, grantId, accountId, benefit.planId(), benefit.id(),
                benefit.value(), credit.ledgerId(), timestamp(now));
        return new SubscriptionRewardGrant(
                "DIAMOND", benefit.value(),
                credit.economy().currencies().getOrDefault("DIAMOND", 0L));
    }

    @Override
    @Transactional
    public SubscriptionDailyRewardResult claimDaily(
            UUID accountId,
            UUID requestId,
            String requestHash,
            SubscriptionPlanCode planCode,
            LocalDate rewardDate,
            Instant now) {
        lock("battle-account:" + accountId);
        List<StoredDaily> byRequest = storedDaily("request_id = ?", requestId);
        if (!byRequest.isEmpty()) {
            StoredDaily stored = byRequest.getFirst();
            if (!stored.accountId().equals(accountId)
                    || !stored.requestHash().equals(requestHash)) {
                throw new SubscriptionException(
                        "SUBSCRIPTION_REWARD_IDEMPOTENCY_CONFLICT",
                        "Idempotency key was already used for another reward.");
            }
            return decode(stored.response()).asReplay();
        }
        List<PlanRow> plans = jdbc.query("""
                select sp.id, ps.id as subscription_id
                  from subscription_plans sp
                  join player_subscriptions ps on ps.plan_id = sp.id
                 where ps.account_id = ? and sp.plan_code = ?
                   and ps.state in ('ACTIVE', 'CANCELED', 'GRACE_PERIOD')
                   and ps.expires_at > ?
                """, (rs, rowNumber) -> new PlanRow(
                rs.getObject("id", UUID.class),
                rs.getObject("subscription_id", UUID.class)),
                accountId, planCode.name(), timestamp(now));
        if (plans.isEmpty()) {
            throw new SubscriptionException(
                    "SUBSCRIPTION_REQUIRED",
                    "The matching monthly subscription is not active.");
        }
        PlanRow plan = plans.getFirst();
        List<StoredDaily> existing = storedDaily(
                "account_id = ? and plan_id = ? and reward_date = ?",
                accountId, plan.id(), java.sql.Date.valueOf(rewardDate));
        if (!existing.isEmpty()) {
            return decode(existing.getFirst().response()).asReplay();
        }
        List<BenefitRow> benefits = jdbc.query("""
                select id, plan_id, benefit_value
                  from subscription_benefit_versions
                 where plan_id = ? and benefit_code = 'DAILY_DIAMOND'
                   and active and valid_from <= ?
                   and (valid_until is null or valid_until > ?)
                """, (rs, rowNumber) -> new BenefitRow(
                rs.getObject("id", UUID.class),
                rs.getObject("plan_id", UUID.class),
                rs.getLong("benefit_value")),
                plan.id(), timestamp(now), timestamp(now));
        if (benefits.isEmpty()) {
            throw new SubscriptionException(
                    "SUBSCRIPTION_REWARD_NOT_FOUND",
                    "Daily subscription reward is not configured.");
        }
        requireBootstrapped(accountId);
        BenefitRow benefit = benefits.getFirst();
        UUID grantId = UUID.randomUUID();
        EconomyCreditResult credit = economyRepository.creditCurrencyWithLedger(
                accountId, requestId, "DIAMOND", benefit.value(),
                "SUBSCRIPTION_DAILY_REWARD",
                "SUBSCRIPTION_DAILY_REWARD", grantId);
        SubscriptionRewardGrant reward = new SubscriptionRewardGrant(
                "DIAMOND", benefit.value(),
                credit.economy().currencies().getOrDefault("DIAMOND", 0L));
        SubscriptionDailyRewardResult result = new SubscriptionDailyRewardResult(
                grantId, planCode, rewardDate, reward, credit.economy(), false);
        jdbc.update("""
                insert into player_subscription_daily_rewards(
                    id, account_id, plan_id, reward_date, request_id,
                    request_hash, benefit_version_id, reward_asset_code,
                    reward_amount, ledger_id, response_payload, granted_at)
                values (?, ?, ?, ?, ?, ?, ?, 'DIAMOND', ?, ?, ?::jsonb, ?)
                """, grantId, accountId, plan.id(),
                java.sql.Date.valueOf(rewardDate), requestId, requestHash,
                benefit.id(), benefit.value(), credit.ledgerId(),
                encode(result), timestamp(now));
        return result;
    }

    private void requireBootstrapped(UUID accountId) {
        if (!economyRepository.findSnapshot(accountId).bootstrapped()) {
            throw new SubscriptionException(
                    "ECONOMY_NOT_BOOTSTRAPPED",
                    "Account economy must be bootstrapped before reward grant.");
        }
    }

    private List<StoredDaily> storedDaily(String predicate, Object... arguments) {
        return jdbc.query("""
                select account_id, request_hash, response_payload::text
                  from player_subscription_daily_rewards where
                """ + predicate,
                (rs, rowNumber) -> new StoredDaily(
                        rs.getObject("account_id", UUID.class),
                        rs.getString("request_hash"),
                        rs.getString("response_payload")), arguments);
    }

    private String encode(SubscriptionDailyRewardResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Cannot serialize subscription daily reward", exception);
        }
    }

    private SubscriptionDailyRewardResult decode(String value) {
        try {
            return objectMapper.readValue(value, SubscriptionDailyRewardResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Cannot deserialize subscription daily reward", exception);
        }
    }

    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { }, key);
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return java.sql.Timestamp.from(value);
    }

    private record BenefitRow(UUID id, UUID planId, long value) {
    }

    private record InitialRow(String assetCode, long amount) {
    }

    private record PlanRow(UUID id, UUID subscriptionId) {
    }

    private record StoredDaily(UUID accountId, String requestHash, String response) {
    }
}
