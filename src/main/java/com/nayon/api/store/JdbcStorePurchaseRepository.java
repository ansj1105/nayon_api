package com.nayon.api.store;

import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import com.nayon.api.store.google.GooglePlayPurchase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcStorePurchaseRepository implements StorePurchaseRepository {

    private static final String SELECT = """
            select r.id, r.account_id, r.request_id, r.request_hash, r.state,
                   o.offer_code, r.store_product_id, r.product_version_id,
                   v.version as reward_version, r.fulfillment_type,
                   v.reward_asset_code,
                   v.reward_amount, r.purchase_token, r.google_order_id,
                   r.google_purchase_time, r.total_asset_balance,
                   r.rejection_code, r.last_failure_code,
                   r.granted_at
              from store_purchase_receipts r
              join store_products p on p.id = r.product_id
              join store_offers o on o.id = p.offer_id
              left join store_product_versions v on v.id = r.product_version_id
            """;

    private final JdbcTemplate jdbc;
    private final EconomyRepository economyRepository;
    private final FirstPurchaseRewardRepository firstPurchaseRewardRepository;

    public JdbcStorePurchaseRepository(
            JdbcTemplate jdbc,
            EconomyRepository economyRepository,
            FirstPurchaseRewardRepository firstPurchaseRewardRepository) {
        this.jdbc = jdbc;
        this.economyRepository = economyRepository;
        this.firstPurchaseRewardRepository = firstPurchaseRewardRepository;
    }

    @Override
    @Transactional
    public StorePurchaseReceipt begin(
            UUID accountId,
            UUID requestId,
            String requestHash,
            String productId,
            String purchaseToken,
            String purchaseTokenHash) {
        lock("store-request:" + requestId);
        lock("store-token:" + purchaseTokenHash);

        List<StorePurchaseReceipt> byRequest = query(
                " where r.request_id = ?", requestId);
        if (!byRequest.isEmpty()) {
            StorePurchaseReceipt existing = byRequest.getFirst();
            if (!existing.accountId().equals(accountId)
                    || !existing.requestHash().equals(requestHash)) {
                throw new StorePurchaseException(
                        "STORE_IDEMPOTENCY_CONFLICT",
                        "Idempotency key was already used for another purchase.");
            }
            return existing.asReplay();
        }

        List<StorePurchaseReceipt> byToken = query(
                " where r.purchase_token_hash = ?", purchaseTokenHash);
        if (!byToken.isEmpty()) {
            StorePurchaseReceipt existing = byToken.getFirst();
            if (!existing.accountId().equals(accountId)
                    || !existing.productId().equals(productId)) {
                throw new StorePurchaseException(
                        "STORE_PURCHASE_TOKEN_CONFLICT",
                        "Purchase token is already owned by another purchase.");
            }
            return existing.asReplay();
        }

        List<UUID> products = jdbc.query("""
                select p.id as product_id
                  from store_products p
                 where p.platform = 'GOOGLE_PLAY'
                   and p.store_product_id = ?
                """, (rs, rowNumber) -> rs.getObject("product_id", UUID.class),
                productId);
        if (products.isEmpty()) {
            throw new StorePurchaseException(
                    "STORE_PRODUCT_NOT_FOUND", "Store product is not configured.");
        }

        UUID receiptId = UUID.randomUUID();
        jdbc.update("""
                insert into store_purchase_receipts(
                    id, account_id, request_id, request_hash, platform,
                    store_product_id, purchase_token, purchase_token_hash,
                    state, product_id)
                values (?, ?, ?, ?, 'GOOGLE_PLAY', ?, ?, ?,
                        'PENDING_VERIFICATION', ?)
                """, receiptId, accountId, requestId, requestHash,
                productId, purchaseToken, purchaseTokenHash,
                products.getFirst());
        return query(" where r.id = ?", receiptId).getFirst();
    }

    @Override
    @Transactional
    public StorePurchaseReceipt grant(
            UUID receiptId,
            UUID accountId,
            GooglePlayPurchase purchase) {
        lock("battle-account:" + accountId);
        List<StorePurchaseReceipt> rows = query(
                " where r.id = ? for update of r", receiptId);
        if (rows.isEmpty() || !rows.getFirst().accountId().equals(accountId)) {
            throw new StorePurchaseException(
                    "STORE_PURCHASE_NOT_FOUND", "Store purchase was not found.");
        }
        StorePurchaseReceipt receipt = rows.getFirst();
        if (receipt.state() != StorePurchaseState.PENDING_VERIFICATION) {
            return receipt.asReplay();
        }
        List<RewardVersion> versions = jdbc.query("""
                select v.id, v.version, v.fulfillment_type,
                       v.reward_asset_code, v.reward_amount
                  from store_purchase_receipts r
                  join store_product_versions v on v.product_id = r.product_id
                 where r.id = ?
                   and v.valid_from <= ?
                   and (v.valid_until is null or v.valid_until > ?)
                 order by v.version desc
                 limit 2
                """, (rs, rowNumber) -> new RewardVersion(
                rs.getObject("id", UUID.class), rs.getInt("version"),
                rs.getString("fulfillment_type"),
                rs.getString("reward_asset_code"),
                rs.getObject("reward_amount", Long.class)),
                receiptId, timestamp(purchase.purchaseTime()),
                timestamp(purchase.purchaseTime()));
        if (versions.size() != 1) {
            throw new StorePurchaseException(
                    "STORE_PRODUCT_VERSION_NOT_FOUND",
                    "No unique reward version matches the Google purchase time.");
        }
        RewardVersion version = versions.getFirst();
        EconomySnapshot current = economyRepository.findSnapshot(accountId);
        if (!current.bootstrapped()) {
            throw new StorePurchaseException(
                    "ECONOMY_NOT_BOOTSTRAPPED",
                    "Account economy must be bootstrapped before purchase grant.");
        }
        Long total = null;
        if (version.fulfillmentType().equals("DIRECT_CURRENCY")) {
            EconomySnapshot economy = economyRepository.creditCurrency(
                    accountId,
                    receipt.requestId(),
                    version.rewardAssetCode(),
                    version.rewardAmount(),
                    "STORE_PURCHASE",
                    "STORE_PURCHASE_RECEIPT",
                    receipt.id());
            total = economy.currencies().getOrDefault(version.rewardAssetCode(), 0L);
        }
        firstPurchaseRewardRepository.grantIfAbsent(
                accountId, receipt.id(), receipt.requestId(), purchase.purchaseTime());
        Instant now = Instant.now();
        jdbc.update("""
                update store_purchase_receipts
                   set state = 'GRANTED', google_order_id = ?,
                       google_purchase_time = ?, verified_at = now(),
                       product_version_id = ?,
                       fulfillment_type = ?,
                       reward_asset_code = ?, reward_amount = ?,
                       total_asset_balance = ?, rejection_code = null,
                       last_failure_code = null, granted_at = ?,
                       updated_at = now(),
                       verification_attempts = verification_attempts + 1
                 where id = ?
                """, purchase.orderId(), timestamp(purchase.purchaseTime()),
                version.id(), version.fulfillmentType(), version.rewardAssetCode(),
                version.rewardAmount(), total,
                Timestamp.from(now), receiptId);
        return query(" where r.id = ?", receiptId).getFirst();
    }

    @Override
    @Transactional
    public StorePurchaseReceipt reject(UUID receiptId, String rejectionCode) {
        jdbc.update("""
                update store_purchase_receipts
                   set state = 'REJECTED', rejection_code = ?,
                       last_failure_code = null, verified_at = now(),
                       verification_attempts = verification_attempts + 1,
                       updated_at = now()
                 where id = ? and state = 'PENDING_VERIFICATION'
                """, rejectionCode, receiptId);
        return query(" where r.id = ?", receiptId).getFirst();
    }

    @Override
    @Transactional
    public void markVerificationFailure(UUID receiptId, String failureCode) {
        jdbc.update("""
                update store_purchase_receipts
                   set last_failure_code = ?,
                       verification_attempts = verification_attempts + 1,
                       updated_at = now()
                 where id = ? and state = 'PENDING_VERIFICATION'
                """, failureCode, receiptId);
    }

    private List<StorePurchaseReceipt> query(String predicate, Object value) {
        return jdbc.query(SELECT + predicate, this::map, value);
    }

    private StorePurchaseReceipt map(ResultSet rs, int rowNumber) throws SQLException {
        return new StorePurchaseReceipt(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getString("request_hash"),
                StorePurchaseState.valueOf(rs.getString("state")),
                rs.getString("offer_code"),
                rs.getString("store_product_id"),
                rs.getObject("product_version_id", UUID.class),
                rs.getInt("reward_version"),
                rs.getString("fulfillment_type"),
                rs.getString("reward_asset_code"),
                rs.getObject("reward_amount", Long.class),
                rs.getString("purchase_token"),
                rs.getString("google_order_id"),
                instant(rs.getTimestamp("google_purchase_time")),
                rs.getObject("total_asset_balance", Long.class),
                rs.getString("rejection_code"),
                rs.getString("last_failure_code"),
                instant(rs.getTimestamp("granted_at")),
                false);
    }

    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { }, key);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record RewardVersion(
            UUID id,
            int version,
            String fulfillmentType,
            String rewardAssetCode,
            Long rewardAmount) {
    }
}
