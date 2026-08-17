package com.nayon.api.subscription;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.store.StoreConfigurationException;
import com.nayon.api.subscription.google.GooglePlaySubscription;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

@Repository
public class JdbcSubscriptionRepository implements SubscriptionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SubscriptionRewardRepository rewardRepository;

    public JdbcSubscriptionRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            SubscriptionRewardRepository rewardRepository) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rewardRepository = rewardRepository;
    }

    @Override
    public SubscriptionCatalog catalog(UUID accountId, String obfuscatedAccountId) {
        List<PlanRow> planRows = jdbc.query("""
                select sp.id, sp.plan_code, sp.reward_track_code,
                       p.store_product_id
                  from subscription_plans sp
                  join store_offers o on o.id = sp.offer_id
                  join store_products p on p.offer_id = o.id
                  join store_product_versions pv on pv.product_id = p.id
                 where sp.active and o.active and p.active and pv.active
                   and p.platform = 'GOOGLE_PLAY'
                   and p.product_type = 'SUBSCRIPTION'
                   and pv.fulfillment_type = 'SUBSCRIPTION'
                   and sp.valid_from <= now()
                   and (sp.valid_until is null or sp.valid_until > now())
                   and pv.valid_from <= now()
                   and (pv.valid_until is null or pv.valid_until > now())
                 order by o.display_order, sp.plan_code
                """, (rs, rowNumber) -> new PlanRow(
                rs.getObject("id", UUID.class),
                SubscriptionPlanCode.valueOf(rs.getString("plan_code")),
                rs.getString("reward_track_code"),
                rs.getString("store_product_id")));
        Map<UUID, List<SubscriptionBenefit>> benefits = new LinkedHashMap<>();
        if (!planRows.isEmpty()) {
            jdbc.query("""
                    select plan_id, benefit_code, benefit_value, version
                      from subscription_benefit_versions
                     where active and valid_from <= now()
                       and (valid_until is null or valid_until > now())
                     order by plan_id, benefit_code
                    """, (RowCallbackHandler) rs -> benefits.computeIfAbsent(
                            rs.getObject("plan_id", UUID.class), ignored -> new ArrayList<>())
                    .add(new SubscriptionBenefit(
                            rs.getString("benefit_code"),
                            rs.getLong("benefit_value"),
                            rs.getInt("version"))));
        }
        List<SubscriptionPlan> plans = planRows.stream()
                .map(row -> new SubscriptionPlan(
                        row.planCode(), row.productId(), row.rewardTrackCode(),
                        benefits.getOrDefault(row.id(), List.of())))
                .toList();
        return new SubscriptionCatalog("GOOGLE_PLAY", obfuscatedAccountId, plans);
    }

    @Override
    public List<PlayerSubscription> findAll(UUID accountId) {
        return jdbc.query("""
                select ps.id, ps.account_id, sp.plan_code, ps.state,
                       ps.started_at, ps.expires_at, ps.auto_renewing,
                       ps.last_verified_at
                  from player_subscriptions ps
                  join subscription_plans sp on sp.id = ps.plan_id
                 where ps.account_id = ?
                 order by sp.plan_code
                """, this::mapSubscription, accountId);
    }

    @Override
    @Transactional
    public SubscriptionVerificationAttempt begin(
            UUID accountId,
            UUID requestId,
            String requestHash,
            String productId,
            String purchaseToken,
            String purchaseTokenHash) {
        lock("battle-account:" + accountId);
        lock("subscription-request:" + requestId);
        lock("subscription-token:" + purchaseTokenHash);

        List<RequestRow> requests = request(requestId);
        if (!requests.isEmpty()) {
            RequestRow existing = requests.getFirst();
            if (!existing.accountId().equals(accountId)
                    || !existing.requestHash().equals(requestHash)) {
                throw new SubscriptionException(
                        "SUBSCRIPTION_IDEMPOTENCY_CONFLICT",
                        "Idempotency key was already used for another request.");
            }
            if ("REJECTED".equals(existing.state())) {
                throw new SubscriptionException(existing.failureCode(),
                        "Subscription verification was rejected.");
            }
            SubscriptionVerificationResult result = existing.responsePayload() == null
                    ? null : decode(existing.responsePayload());
            return new SubscriptionVerificationAttempt(
                    existing.subscriptionId(), existing.planCode(),
                    existing.productId(), true, result);
        }

        ProductPlan productPlan = productPlan(productId);
        List<RequestTokenOwner> requestTokenOwners = jdbc.query("""
                select account_id, store_product_id
                  from subscription_verification_requests
                 where purchase_token_hash = ?
                 order by created_at
                 limit 1
                """, (rs, rowNumber) -> new RequestTokenOwner(
                rs.getObject("account_id", UUID.class),
                rs.getString("store_product_id")), purchaseTokenHash);
        if (!requestTokenOwners.isEmpty()) {
            RequestTokenOwner owner = requestTokenOwners.getFirst();
            if (!owner.accountId().equals(accountId)
                    || !owner.productId().equals(productId)) {
                throw new SubscriptionException(
                        "SUBSCRIPTION_PURCHASE_TOKEN_CONFLICT",
                        "Purchase token is already owned by another subscription.");
            }
        }
        List<TokenRow> tokenRows = jdbc.query("""
                select ps.id, ps.account_id, ps.plan_id, sp.plan_code,
                       p.store_product_id, ps.state
                  from player_subscriptions ps
                  join subscription_plans sp on sp.id = ps.plan_id
                  join store_offers o on o.id = sp.offer_id
                 join store_products p on p.offer_id = o.id
                 where ps.purchase_token_hash = ?
                   and p.active
                   and p.platform = 'GOOGLE_PLAY'
                   and p.product_type = 'SUBSCRIPTION'
                """, (rs, rowNumber) -> new TokenRow(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getObject("plan_id", UUID.class),
                SubscriptionPlanCode.valueOf(rs.getString("plan_code")),
                rs.getString("store_product_id"),
                SubscriptionState.valueOf(rs.getString("state"))),
                purchaseTokenHash);
        UUID subscriptionId;
        if (!tokenRows.isEmpty()) {
            TokenRow token = tokenRows.getFirst();
            if (!token.accountId().equals(accountId)
                    || !token.productId().equals(productId)) {
                throw new SubscriptionException(
                        "SUBSCRIPTION_PURCHASE_TOKEN_CONFLICT",
                        "Purchase token is already owned by another subscription.");
            }
            subscriptionId = token.id();
            if (token.state() != SubscriptionState.PENDING) {
                SubscriptionRewardGrant initialReward =
                        rewardRepository.grantInitialIfEligible(
                                accountId, subscriptionId, requestId, Instant.now());
                SubscriptionVerificationResult replay = result(
                        find(subscriptionId).asReplay(), initialReward, true);
                insertRequest(requestId, accountId, subscriptionId,
                        requestHash, productId, purchaseToken, purchaseTokenHash,
                        "COMPLETED", encode(replay), null);
                return new SubscriptionVerificationAttempt(
                        subscriptionId, token.planCode(), productId, true, replay);
            }
        } else {
            List<UUID> accountPlan = jdbc.query("""
                    select id from player_subscriptions
                     where account_id = ? and plan_id = ?
                     for update
                    """, (rs, rowNumber) -> rs.getObject("id", UUID.class),
                    accountId, productPlan.planId());
            if (accountPlan.isEmpty()) {
                subscriptionId = UUID.randomUUID();
                jdbc.update("""
                        insert into player_subscriptions(
                            id, account_id, plan_id, purchase_token,
                            purchase_token_hash, state, acknowledgement_state)
                        values (?, ?, ?, ?, ?, 'PENDING', 'PENDING')
                        """, subscriptionId, accountId, productPlan.planId(),
                        purchaseToken, purchaseTokenHash);
            } else {
                subscriptionId = accountPlan.getFirst();
                Long pending = jdbc.queryForObject("""
                        select count(*) from subscription_verification_requests
                         where subscription_id = ? and state = 'PENDING'
                           and purchase_token_hash <> ?
                        """, Long.class, subscriptionId, purchaseTokenHash);
                if (pending != null && pending > 0) {
                    throw new SubscriptionException(
                            "SUBSCRIPTION_VERIFICATION_IN_PROGRESS",
                            "Another subscription verification is in progress.");
                }
            }
        }
        insertRequest(requestId, accountId, subscriptionId,
                requestHash, productId, purchaseToken, purchaseTokenHash,
                "PENDING", null, null);
        return new SubscriptionVerificationAttempt(
                subscriptionId, productPlan.planCode(), productId, false, null);
    }

    @Override
    @Transactional
    public SubscriptionVerificationResult complete(
            UUID accountId,
            UUID requestId,
            GooglePlaySubscription subscription,
            Instant verifiedAt) {
        lock("battle-account:" + accountId);
        List<RequestRow> requests = request(requestId);
        if (requests.isEmpty() || !requests.getFirst().accountId().equals(accountId)) {
            throw new SubscriptionException(
                    "SUBSCRIPTION_VERIFICATION_NOT_FOUND",
                    "Subscription verification request was not found.");
        }
        RequestRow request = requests.getFirst();
        if ("COMPLETED".equals(request.state())) {
            return decode(request.responsePayload()).asReplay();
        }
        if (!"PENDING".equals(request.state())) {
            throw new SubscriptionException(request.failureCode(),
                    "Subscription verification was rejected.");
        }
        jdbc.update("""
                update player_subscriptions
                   set purchase_token = ?, purchase_token_hash = ?,
                       state = ?, started_at = ?, expires_at = ?,
                       auto_renewing = ?, acknowledgement_state = ?,
                       google_order_id = ?, linked_purchase_token_hash = ?,
                       last_verified_at = ?, last_failure_code = null,
                       updated_at = now()
                 where id = ? and account_id = ?
                """, request.purchaseToken(), request.purchaseTokenHash(),
                subscription.state().name(), timestamp(subscription.startedAt()),
                timestamp(subscription.expiresAt()), subscription.autoRenewing(),
                subscription.acknowledged() ? "ACKNOWLEDGED" : "PENDING",
                subscription.orderId(),
                subscription.linkedPurchaseToken() == null
                        ? null : sha256(subscription.linkedPurchaseToken()),
                timestamp(verifiedAt), request.subscriptionId(), accountId);
        SubscriptionRewardGrant initialReward =
                rewardRepository.grantInitialIfEligible(
                        accountId, request.subscriptionId(), requestId, verifiedAt);
        SubscriptionVerificationResult result = result(
                find(request.subscriptionId()), initialReward, false);
        jdbc.update("""
                update subscription_verification_requests
                   set state = 'COMPLETED', response_payload = ?::jsonb,
                       failure_code = null, updated_at = now()
                 where request_id = ? and state = 'PENDING'
                """, encode(result), requestId);
        return result;
    }

    @Override
    @Transactional
    public void fail(UUID requestId, String code, boolean terminal) {
        List<RequestRow> requests = request(requestId);
        if (requests.isEmpty() || !"PENDING".equals(requests.getFirst().state())) {
            return;
        }
        RequestRow request = requests.getFirst();
        if (terminal) {
            jdbc.update("""
                    update subscription_verification_requests
                       set state = 'REJECTED', failure_code = ?, updated_at = now()
                     where request_id = ? and state = 'PENDING'
                    """, code, requestId);
        }
        jdbc.update("""
                update player_subscriptions
                   set last_failure_code = ?, updated_at = now()
                 where id = ?
                """, code, request.subscriptionId());
    }

    @Override
    public Optional<SubscriptionTokenOwner> findByTokenHash(String purchaseTokenHash) {
        return jdbc.query("""
                select ps.account_id, p.store_product_id,
                       ps.purchase_token_hash
                  from player_subscriptions ps
                  join subscription_plans sp on sp.id = ps.plan_id
                  join store_offers o on o.id = sp.offer_id
                  join store_products p on p.offer_id = o.id
                 where ps.purchase_token_hash = ?
                   and p.active and p.platform = 'GOOGLE_PLAY'
                   and p.product_type = 'SUBSCRIPTION'
                """, (rs, rowNumber) -> new SubscriptionTokenOwner(
                rs.getObject("account_id", UUID.class),
                rs.getString("store_product_id"),
                rs.getString("purchase_token_hash")), purchaseTokenHash)
                .stream().findFirst();
    }

    @Override
    @Transactional
    public PlayerSubscription reconcile(
            SubscriptionTokenOwner owner,
            GooglePlaySubscription subscription,
            Instant verifiedAt) {
        lock("battle-account:" + owner.accountId());
        int updated = jdbc.update("""
                update player_subscriptions
                   set state = ?, started_at = ?, expires_at = ?,
                       auto_renewing = ?, acknowledgement_state = ?,
                       google_order_id = ?, linked_purchase_token_hash = ?,
                       last_verified_at = ?, last_failure_code = null,
                       updated_at = now()
                 where account_id = ? and purchase_token_hash = ?
                """, subscription.state().name(), timestamp(subscription.startedAt()),
                timestamp(subscription.expiresAt()), subscription.autoRenewing(),
                subscription.acknowledged() ? "ACKNOWLEDGED" : "PENDING",
                subscription.orderId(),
                subscription.linkedPurchaseToken() == null
                        ? null : sha256(subscription.linkedPurchaseToken()),
                timestamp(verifiedAt), owner.accountId(), owner.purchaseTokenHash());
        if (updated != 1) {
            throw new SubscriptionException(
                    "SUBSCRIPTION_TOKEN_NOT_FOUND",
                    "Subscription token is not registered.");
        }
        return findAll(owner.accountId()).stream()
                .filter(value -> value.planCode() == productPlan(owner.productId()).planCode())
                .findFirst()
                .orElseThrow(() -> new SubscriptionException(
                        "SUBSCRIPTION_NOT_FOUND", "Subscription was not found."));
    }

    private ProductPlan productPlan(String productId) {
        List<ProductPlan> rows = jdbc.query("""
                select sp.id, sp.plan_code
                  from subscription_plans sp
                  join store_offers o on o.id = sp.offer_id
                  join store_products p on p.offer_id = o.id
                  join store_product_versions pv on pv.product_id = p.id
                 where sp.active and o.active and p.active and pv.active
                   and p.platform = 'GOOGLE_PLAY'
                   and p.product_type = 'SUBSCRIPTION'
                   and pv.fulfillment_type = 'SUBSCRIPTION'
                   and p.store_product_id = ?
                   and sp.valid_from <= now()
                   and (sp.valid_until is null or sp.valid_until > now())
                   and pv.valid_from <= now()
                   and (pv.valid_until is null or pv.valid_until > now())
                """, (rs, rowNumber) -> new ProductPlan(
                rs.getObject("id", UUID.class),
                SubscriptionPlanCode.valueOf(rs.getString("plan_code"))),
                productId);
        if (rows.isEmpty()) {
            throw new SubscriptionException(
                    "SUBSCRIPTION_PRODUCT_NOT_FOUND",
                    "Subscription product is not configured.");
        }
        if (rows.size() != 1) {
            throw new StoreConfigurationException(
                    "Subscription product maps to multiple active plans.");
        }
        return rows.getFirst();
    }

    private List<RequestRow> request(UUID requestId) {
        return jdbc.query("""
                select vr.account_id, vr.subscription_id, vr.request_hash,
                       vr.store_product_id, vr.purchase_token,
                       vr.purchase_token_hash,
                       vr.state, vr.response_payload::text, vr.failure_code,
                       sp.plan_code
                  from subscription_verification_requests vr
                  join player_subscriptions ps on ps.id = vr.subscription_id
                  join subscription_plans sp on sp.id = ps.plan_id
                 where vr.request_id = ?
                 for update of vr
                """, (rs, rowNumber) -> new RequestRow(
                rs.getObject("account_id", UUID.class),
                rs.getObject("subscription_id", UUID.class),
                rs.getString("request_hash"), rs.getString("state"),
                rs.getString("response_payload"), rs.getString("failure_code"),
                SubscriptionPlanCode.valueOf(rs.getString("plan_code")),
                rs.getString("store_product_id"),
                rs.getString("purchase_token"),
                rs.getString("purchase_token_hash")), requestId);
    }

    private PlayerSubscription find(UUID subscriptionId) {
        List<PlayerSubscription> rows = jdbc.query("""
                select ps.id, ps.account_id, sp.plan_code, ps.state,
                       ps.started_at, ps.expires_at, ps.auto_renewing,
                       ps.last_verified_at
                  from player_subscriptions ps
                  join subscription_plans sp on sp.id = ps.plan_id
                 where ps.id = ?
                """, this::mapSubscription, subscriptionId);
        if (rows.isEmpty()) {
            throw new SubscriptionException(
                    "SUBSCRIPTION_NOT_FOUND", "Subscription was not found.");
        }
        return rows.getFirst();
    }

    private PlayerSubscription mapSubscription(ResultSet rs, int rowNumber)
            throws SQLException {
        return PlayerSubscription.snapshot(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                SubscriptionPlanCode.valueOf(rs.getString("plan_code")),
                SubscriptionState.valueOf(rs.getString("state")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("expires_at")),
                rs.getBoolean("auto_renewing"),
                instant(rs.getTimestamp("last_verified_at")), false);
    }

    private void insertRequest(
            UUID requestId, UUID accountId, UUID subscriptionId,
            String requestHash, String productId,
            String purchaseToken, String purchaseTokenHash,
            String state, String response, String failureCode) {
        jdbc.update("""
                insert into subscription_verification_requests(
                    request_id, account_id, subscription_id, request_hash,
                    store_product_id, purchase_token, purchase_token_hash,
                    state, response_payload, failure_code)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, requestId, accountId, subscriptionId, requestHash,
                productId, purchaseToken, purchaseTokenHash,
                state, response, failureCode);
    }

    private SubscriptionVerificationResult result(
            PlayerSubscription subscription, boolean replay) {
        return result(subscription, null, replay);
    }

    private SubscriptionVerificationResult result(
            PlayerSubscription subscription,
            SubscriptionRewardGrant initialReward,
            boolean replay) {
        return new SubscriptionVerificationResult(
                subscription, initialReward, replay);
    }

    private String encode(SubscriptionVerificationResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize subscription result", exception);
        }
    }

    private SubscriptionVerificationResult decode(String value) {
        try {
            return objectMapper.readValue(value, SubscriptionVerificationResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot deserialize subscription result", exception);
        }
    }

    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { }, key);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record PlanRow(
            UUID id, SubscriptionPlanCode planCode,
            String rewardTrackCode, String productId) {
    }

    private record ProductPlan(UUID planId, SubscriptionPlanCode planCode) {
    }

    private record TokenRow(
            UUID id, UUID accountId, UUID planId,
            SubscriptionPlanCode planCode, String productId,
            SubscriptionState state) {
    }

    private record RequestRow(
            UUID accountId, UUID subscriptionId, String requestHash,
            String state, String responsePayload, String failureCode,
            SubscriptionPlanCode planCode, String productId,
            String purchaseToken, String purchaseTokenHash) {
    }

    private record RequestTokenOwner(UUID accountId, String productId) {
    }
}
