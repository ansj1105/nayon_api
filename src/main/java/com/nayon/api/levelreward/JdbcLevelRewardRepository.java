package com.nayon.api.levelreward;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.economy.EconomyCreditResult;
import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import com.nayon.api.subscription.PlayerSubscription;
import com.nayon.api.subscription.SubscriptionPlanCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcLevelRewardRepository implements LevelRewardRepository {

    private final JdbcTemplate jdbc;
    private final EconomyRepository economyRepository;
    private final ObjectMapper objectMapper;

    public JdbcLevelRewardRepository(
            JdbcTemplate jdbc,
            EconomyRepository economyRepository,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.economyRepository = economyRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public long totalAccountExp(UUID accountId) {
        List<Long> values = jdbc.query("""
                select account_exp from player_progression where account_id = ?
                """, (rs, rowNumber) -> rs.getLong(1), accountId);
        return values.isEmpty() ? 0L : values.getFirst();
    }

    @Override
    public List<LevelRewardItem> findAll(
            UUID accountId,
            int accountLevel,
            List<PlayerSubscription> subscriptions,
            Instant now) {
        Set<String> claimed = new HashSet<>(jdbc.query("""
                select track_code || ':' || required_level
                  from player_level_reward_claims where account_id = ?
                """, (rs, rowNumber) -> rs.getString(1), accountId));
        boolean premium = entitled(
                subscriptions, SubscriptionPlanCode.MONTHLY_GROWTH, now);
        boolean royal = entitled(
                subscriptions, SubscriptionPlanCode.MONTHLY_ADVANCED, now);
        return jdbc.query("""
                select catalog_version, track_code, required_level,
                       reward_asset_type, reward_asset_code, reward_amount
                  from level_reward_versions
                 where active and valid_from <= ?
                   and (valid_until is null or valid_until > ?)
                 order by required_level, track_code
                """, (rs, rowNumber) -> {
            LevelRewardTrackCode track = LevelRewardTrackCode.valueOf(
                    rs.getString("track_code"));
            int level = rs.getInt("required_level");
            boolean alreadyClaimed = claimed.contains(track + ":" + level);
            boolean trackEntitled = track == LevelRewardTrackCode.FREE
                    || track == LevelRewardTrackCode.PREMIUM && premium
                    || track == LevelRewardTrackCode.ROYAL && royal;
            return new LevelRewardItem(
                    rs.getInt("catalog_version"), track, level,
                    rs.getString("reward_asset_type"),
                    rs.getString("reward_asset_code"),
                    rs.getLong("reward_amount"), alreadyClaimed,
                    !alreadyClaimed && accountLevel >= level && trackEntitled);
        }, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
    }

    @Override
    @Transactional
    public LevelRewardClaimResult claim(
            UUID accountId,
            UUID requestId,
            String requestHash,
            LevelRewardTrackCode trackCode,
            int requiredLevel,
            int accountLevel,
            Instant now) {
        lock("battle-account:" + accountId);
        List<StoredClaim> byRequest = stored("request_id = ?", requestId);
        if (!byRequest.isEmpty()) {
            StoredClaim stored = byRequest.getFirst();
            if (!stored.accountId().equals(accountId)
                    || !stored.requestHash().equals(requestHash)) {
                throw new LevelRewardException(
                        "LEVEL_REWARD_IDEMPOTENCY_CONFLICT",
                        "Idempotency key was already used for another reward.");
            }
            return decode(stored.response()).asReplay();
        }
        List<StoredClaim> existing = stored(
                "account_id = ? and track_code = ? and required_level = ?",
                accountId, trackCode.name(), requiredLevel);
        if (!existing.isEmpty()) {
            return decode(existing.getFirst().response()).asReplay();
        }
        List<RewardRow> rewards = jdbc.query("""
                select id, catalog_version, reward_asset_type,
                       reward_asset_code, reward_amount
                  from level_reward_versions
                 where track_code = ? and required_level = ?
                   and active and valid_from <= ?
                   and (valid_until is null or valid_until > ?)
                """, (rs, rowNumber) -> new RewardRow(
                rs.getObject("id", UUID.class),
                rs.getInt("catalog_version"),
                rs.getString("reward_asset_type"),
                rs.getString("reward_asset_code"),
                rs.getLong("reward_amount")),
                trackCode.name(), requiredLevel,
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        if (rewards.isEmpty()) {
            throw new LevelRewardException(
                    "LEVEL_REWARD_NOT_FOUND", "Level reward is not configured.");
        }
        if (accountLevel < requiredLevel) {
            throw new LevelRewardException(
                    "LEVEL_REWARD_LEVEL_REQUIRED",
                    "Required account level has not been reached.");
        }
        if (trackCode != LevelRewardTrackCode.FREE
                && !hasEntitlement(accountId, trackCode, now)) {
            throw new LevelRewardException(
                    "LEVEL_REWARD_SUBSCRIPTION_REQUIRED",
                    "The matching monthly subscription is not active.");
        }
        EconomySnapshot current = economyRepository.findSnapshot(accountId);
        if (!current.bootstrapped()) {
            throw new LevelRewardException(
                    "ECONOMY_NOT_BOOTSTRAPPED",
                    "Account economy must be bootstrapped before reward claim.");
        }
        RewardRow reward = rewards.getFirst();
        UUID claimId = UUID.randomUUID();
        EconomyCreditResult credit = "CURRENCY".equals(reward.assetType())
                ? economyRepository.creditCurrencyWithLedger(
                        accountId, requestId, reward.assetCode(), reward.amount(),
                        "LEVEL_REWARD", "LEVEL_REWARD_CLAIM", claimId)
                : economyRepository.creditItemWithLedger(
                        accountId, requestId, reward.assetCode(), reward.amount(),
                        "LEVEL_REWARD", "LEVEL_REWARD_CLAIM", claimId);
        LevelRewardItem item = new LevelRewardItem(
                reward.version(), trackCode, requiredLevel,
                reward.assetType(), reward.assetCode(), reward.amount(),
                true, false);
        LevelRewardClaimResult result = new LevelRewardClaimResult(
                claimId, item, credit.economy(), false);
        jdbc.update("""
                insert into player_level_reward_claims(
                    id, account_id, request_id, request_hash, track_code,
                    required_level, reward_version_id, reward_asset_type,
                    reward_asset_code, reward_amount, ledger_id,
                    response_payload, claimed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, claimId, accountId, requestId, requestHash,
                trackCode.name(), requiredLevel, reward.id(), reward.assetType(),
                reward.assetCode(), reward.amount(), credit.ledgerId(),
                encode(result), java.sql.Timestamp.from(now));
        return result;
    }

    private boolean hasEntitlement(
            UUID accountId, LevelRewardTrackCode trackCode, Instant now) {
        String plan = trackCode == LevelRewardTrackCode.PREMIUM
                ? SubscriptionPlanCode.MONTHLY_GROWTH.name()
                : SubscriptionPlanCode.MONTHLY_ADVANCED.name();
        Long count = jdbc.queryForObject("""
                select count(*)
                  from player_subscriptions ps
                  join subscription_plans sp on sp.id = ps.plan_id
                 where ps.account_id = ? and sp.plan_code = ?
                   and ps.state in ('ACTIVE', 'CANCELED', 'GRACE_PERIOD')
                   and ps.expires_at > ?
                """, Long.class, accountId, plan, java.sql.Timestamp.from(now));
        return count != null && count == 1;
    }

    private boolean entitled(
            List<PlayerSubscription> subscriptions,
            SubscriptionPlanCode plan,
            Instant now) {
        return subscriptions.stream().anyMatch(value ->
                value.planCode() == plan && value.entitled(now));
    }

    private List<StoredClaim> stored(String predicate, Object... arguments) {
        return jdbc.query("""
                select account_id, request_hash, response_payload::text
                  from player_level_reward_claims where
                """ + predicate,
                (rs, rowNumber) -> new StoredClaim(
                        rs.getObject("account_id", UUID.class),
                        rs.getString("request_hash"),
                        rs.getString("response_payload")), arguments);
    }

    private String encode(LevelRewardClaimResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize level reward", exception);
        }
    }

    private LevelRewardClaimResult decode(String value) {
        try {
            return objectMapper.readValue(value, LevelRewardClaimResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot deserialize level reward", exception);
        }
    }

    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { }, key);
    }

    private record RewardRow(
            UUID id, int version, String assetType,
            String assetCode, long amount) {
    }

    private record StoredClaim(UUID accountId, String requestHash, String response) {
    }
}
