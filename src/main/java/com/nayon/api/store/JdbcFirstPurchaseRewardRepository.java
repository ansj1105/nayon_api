package com.nayon.api.store;

import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import com.nayon.api.gacha.GachaAward;
import com.nayon.api.gacha.GachaEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcFirstPurchaseRewardRepository
        implements FirstPurchaseRewardRepository {

    private static final String SELECT = """
            select r.id, r.account_id, r.qualifying_receipt_id,
                   v.version, v.equipment_catalog_version,
                   r.equipment_id, r.equipment_code, r.equipment_grade,
                   r.diamond_amount, r.gold_amount,
                   r.diamond_balance, r.gold_balance, r.granted_at
              from player_first_purchase_rewards r
              join first_purchase_reward_versions v on v.id = r.reward_version_id
            """;
    private static final String REASON = "FIRST_PURCHASE_REWARD";
    private static final String REFERENCE = "PLAYER_FIRST_PURCHASE_REWARD";

    private final JdbcTemplate jdbc;
    private final EconomyRepository economyRepository;
    private final GachaEngine gachaEngine;

    public JdbcFirstPurchaseRewardRepository(
            JdbcTemplate jdbc,
            EconomyRepository economyRepository,
            GachaEngine gachaEngine) {
        this.jdbc = jdbc;
        this.economyRepository = economyRepository;
        this.gachaEngine = gachaEngine;
    }

    @Override
    public Optional<FirstPurchaseReward> findByAccount(UUID accountId) {
        return query(" where r.account_id = ?", accountId).stream().findFirst();
    }

    @Override
    public Optional<FirstPurchaseReward> findByReceipt(UUID receiptId) {
        return query(" where r.qualifying_receipt_id = ?", receiptId)
                .stream().findFirst();
    }

    @Override
    public FirstPurchaseReward grantIfAbsent(
            UUID accountId,
            UUID receiptId,
            UUID requestId,
            Instant purchaseTime) {
        Optional<FirstPurchaseReward> existing = findByAccount(accountId);
        if (existing.isPresent()) {
            return existing.get();
        }

        List<RewardVersion> versions = jdbc.query("""
                select id, version, equipment_catalog_version,
                       equipment_grade, diamond_amount, gold_amount
                  from first_purchase_reward_versions
                 where valid_from <= ?
                   and (valid_until is null or valid_until > ?)
                 order by version desc
                 limit 2
                """, (rs, rowNumber) -> new RewardVersion(
                rs.getObject("id", UUID.class),
                rs.getInt("version"),
                rs.getString("equipment_catalog_version"),
                rs.getString("equipment_grade"),
                rs.getLong("diamond_amount"),
                rs.getLong("gold_amount")),
                Timestamp.from(purchaseTime), Timestamp.from(purchaseTime));
        if (versions.size() != 1) {
            throw new StorePurchaseException(
                    "FIRST_PURCHASE_REWARD_VERSION_NOT_FOUND",
                    "No unique first-purchase reward version matches the purchase time.");
        }
        RewardVersion version = versions.getFirst();
        if (!gachaEngine.catalogVersion().equals(version.catalogVersion())) {
            throw new StorePurchaseException(
                    "FIRST_PURCHASE_REWARD_CATALOG_MISMATCH",
                    "First-purchase reward catalog version is unavailable.");
        }

        UUID rewardId = UUID.randomUUID();
        GachaAward equipment = gachaEngine.drawEquipment(
                version.equipmentGrade(), false);
        jdbc.update("""
                insert into player_equipment(
                    id, account_id, equipment_code, grade,
                    source_type, source_id)
                values (?, ?, ?, ?, 'FIRST_PURCHASE_REWARD', ?)
                """, equipment.equipmentId(), accountId,
                equipment.equipmentCode(), equipment.grade(), rewardId);

        economyRepository.creditCurrency(
                accountId, requestId, "DIAMOND", version.diamondAmount(),
                REASON, REFERENCE, rewardId);
        EconomySnapshot economy = economyRepository.creditCurrency(
                accountId, requestId, "GOLD", version.goldAmount(),
                REASON, REFERENCE, rewardId);
        long diamondBalance = economy.currencies().getOrDefault("DIAMOND", 0L);
        long goldBalance = economy.currencies().getOrDefault("GOLD", 0L);
        Instant grantedAt = Instant.now();

        jdbc.update("""
                insert into player_first_purchase_rewards(
                    id, account_id, qualifying_receipt_id, reward_version_id,
                    equipment_id, equipment_code, equipment_grade,
                    diamond_amount, gold_amount,
                    diamond_balance, gold_balance, granted_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rewardId, accountId, receiptId, version.id(),
                equipment.equipmentId(), equipment.equipmentCode(),
                equipment.grade(), version.diamondAmount(), version.goldAmount(),
                diamondBalance, goldBalance, Timestamp.from(grantedAt));
        return findByAccount(accountId).orElseThrow();
    }

    private List<FirstPurchaseReward> query(String predicate, UUID value) {
        return jdbc.query(SELECT + predicate, this::map, value);
    }

    private FirstPurchaseReward map(ResultSet rs, int rowNumber) throws SQLException {
        UUID accountId = rs.getObject("account_id", UUID.class);
        return new FirstPurchaseReward(
                rs.getObject("id", UUID.class), accountId,
                rs.getObject("qualifying_receipt_id", UUID.class),
                rs.getInt("version"), rs.getString("equipment_catalog_version"),
                rs.getObject("equipment_id", UUID.class),
                rs.getString("equipment_code"), rs.getString("equipment_grade"),
                rs.getLong("diamond_amount"), rs.getLong("gold_amount"),
                rs.getLong("diamond_balance"), rs.getLong("gold_balance"),
                rs.getTimestamp("granted_at").toInstant(),
                economyRepository.findSnapshot(accountId));
    }

    private record RewardVersion(
            UUID id,
            int version,
            String catalogVersion,
            String equipmentGrade,
            long diamondAmount,
            long goldAmount) {
    }
}
