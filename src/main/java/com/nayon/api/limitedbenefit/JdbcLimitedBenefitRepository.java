package com.nayon.api.limitedbenefit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import com.nayon.api.limitedbenefit.admob.AdMobRewardCallbackResult;
import com.nayon.api.limitedbenefit.admob.AdMobSsvCallback;
import com.nayon.api.limitedbenefit.admob.LimitedBenefitAdSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcLimitedBenefitRepository {
    private final JdbcTemplate jdbc;
    private final EconomyRepository economyRepository;
    private final ObjectMapper objectMapper;

    public JdbcLimitedBenefitRepository(
            JdbcTemplate jdbc,
            EconomyRepository economyRepository,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.economyRepository = economyRepository;
        this.objectMapper = objectMapper;
    }

    public Optional<LimitedBenefitCampaign> findCurrent(
            UUID accountId, Instant now, LocalDate cycleDate, Instant resetsAt) {
        List<CampaignRow> campaigns = jdbc.query("""
                select id, campaign_code, version
                  from limited_benefit_campaign_versions
                 where campaign_code = 'daily_limited_benefit'
                   and active and valid_from <= ?
                   and (valid_until is null or valid_until > ?)
                 order by version desc limit 1
                """, this::mapCampaign, Timestamp.from(now), Timestamp.from(now));
        if (campaigns.isEmpty()) {
            return Optional.empty();
        }

        CampaignRow campaign = campaigns.getFirst();
        List<OfferRow> rows = jdbc.query("""
                select o.id, o.offer_code, o.display_order, o.title,
                       o.fulfillment_type, o.provider_key, o.store_offer_id,
                       (select sp.store_product_id
                          from store_products sp
                         where sp.offer_id = o.store_offer_id
                           and sp.platform = 'GOOGLE_PLAY' and sp.active
                         limit 1) as product_id,
                       exists(select 1 from player_limited_benefit_claims c
                               where c.account_id = ?
                                 and c.campaign_version_id = o.campaign_version_id
                                 and c.cycle_date = ? and c.offer_id = o.id) as claimed
                  from limited_benefit_offers o
                 where o.campaign_version_id = ?
                 order by o.display_order
                """, this::mapOffer, accountId, cycleDate, campaign.id());
        Map<UUID, List<LimitedBenefitReward>> rewards = loadRewards(campaign.id());

        List<LimitedBenefitOffer> offers = new ArrayList<>(rows.size());
        boolean predecessorClaimed = true;
        for (OfferRow row : rows) {
            String state;
            if (row.claimed()) {
                state = "CLAIMED";
            } else if (!predecessorClaimed) {
                state = "LOCKED";
            } else if (!providerAvailable(row)) {
                state = "PROVIDER_UNAVAILABLE";
            } else {
                state = "AVAILABLE";
            }
            offers.add(new LimitedBenefitOffer(
                    row.id(), row.offerCode(), row.displayOrder(), row.title(),
                    row.fulfillmentType(), row.storeOfferId(), row.productId(), state,
                    rewards.getOrDefault(row.id(), List.of())));
            predecessorClaimed = row.claimed();
        }
        return Optional.of(new LimitedBenefitCampaign(
                campaign.id(), campaign.code(), campaign.version(), now,
                cycleDate, resetsAt, offers));
    }

    public LimitedBenefitClaimResult claim(
            UUID accountId,
            UUID requestId,
            String offerCode,
            UUID receiptId,
            UUID adSessionId,
            Instant now,
            LocalDate cycleDate) {
        if (offerCode == null || !offerCode.matches("^[a-z][a-z0-9_]{2,63}$")) {
            throw new IllegalArgumentException("Invalid limited benefit offer code");
        }
        lock("limited-benefit-account:" + accountId);
        lock("limited-benefit-request:" + requestId);
        if (receiptId != null && adSessionId != null) {
            throw invalidProof();
        }
        String proofType = receiptId != null ? "GOOGLE_PLAY"
                : adSessionId != null ? "ADMOB_SSV" : "FREE";
        String requestHash = switch (proofType) {
            case "FREE" -> sha256(offerCode + "\nFREE");
            case "GOOGLE_PLAY" -> sha256(
                    offerCode + "\nGOOGLE_PLAY\n" + receiptId);
            default -> sha256(offerCode + "\nADMOB_SSV\n" + adSessionId);
        };

        List<StoredClaim> existing = jdbc.query("""
                select account_id, request_hash, response_payload::text
                  from player_limited_benefit_claims where request_id = ?
                """, this::mapStoredClaim, requestId);
        if (!existing.isEmpty()) {
            StoredClaim stored = existing.getFirst();
            if (!stored.accountId().equals(accountId)
                    || !stored.requestHash().equals(requestHash)) {
                throw conflict("LIMITED_BENEFIT_IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key was already used for another claim.");
            }
            return readResult(stored.responsePayload()).asReplay();
        }

        EconomySnapshot economy = economyRepository.findSnapshot(accountId);
        if (!economy.bootstrapped()) {
            throw conflict("ECONOMY_NOT_BOOTSTRAPPED",
                    "Economy must be bootstrapped before claiming rewards.");
        }
        Instant resetsAt = cycleDate.plusDays(1)
                .atStartOfDay(LimitedBenefitService.CAMPAIGN_ZONE).toInstant();
        LimitedBenefitCampaign campaign = findCurrent(accountId, now, cycleDate, resetsAt)
                .orElseThrow(() -> conflict("LIMITED_BENEFIT_NOT_ACTIVE",
                        "No limited benefit campaign is active."));
        LimitedBenefitOffer offer = campaign.offers().stream()
                .filter(value -> value.offerCode().equals(offerCode))
                .findFirst()
                .orElseThrow(() -> new LimitedBenefitException(
                        "LIMITED_BENEFIT_OFFER_NOT_FOUND", "Offer was not found."));
        if (!offer.fulfillmentType().equals(proofType)) {
            throw new LimitedBenefitException("LIMITED_BENEFIT_PROOF_REQUIRED",
                    "This offer requires provider proof.");
        }
        if (!offer.state().equals("AVAILABLE")) {
            throw conflict("LIMITED_BENEFIT_OFFER_" + offer.state(),
                    "Offer cannot be claimed in its current state.");
        }
        if (proofType.equals("GOOGLE_PLAY")) {
            validateGoogleReceipt(
                    accountId, receiptId, offer.storeOfferId(), cycleDate);
        } else if (proofType.equals("ADMOB_SSV")) {
            validateAdSession(
                    accountId, adSessionId, offer.id(), cycleDate);
        }

        UUID claimId = UUID.randomUUID();
        for (LimitedBenefitReward reward : offer.rewards()) {
            if (reward.type().equals("CURRENCY")) {
                economy = economyRepository.creditCurrency(
                        accountId, requestId, reward.code(), reward.amount(),
                        "LIMITED_BENEFIT", "PLAYER_LIMITED_BENEFIT_CLAIM", claimId);
            } else if (reward.type().equals("ITEM")
                    || reward.type().equals("EQUIPMENT_BOX")) {
                economy = economyRepository.creditItem(
                        accountId, requestId, reward.code(), reward.amount(),
                        "LIMITED_BENEFIT", "PLAYER_LIMITED_BENEFIT_CLAIM", claimId);
            }
        }
        LimitedBenefitClaimResult result = new LimitedBenefitClaimResult(
                claimId, offerCode, cycleDate, offer.rewards(), economy, false);
        jdbc.update("""
                insert into player_limited_benefit_claims(
                    id, account_id, campaign_version_id, offer_id, cycle_date,
                    request_id, request_hash, proof_type, receipt_id, ad_session_id,
                    response_payload, claimed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, claimId, accountId, campaign.campaignVersionId(), offer.id(),
                cycleDate, requestId, requestHash, proofType, receiptId, adSessionId,
                writeResult(result), Timestamp.from(now));
        if (proofType.equals("ADMOB_SSV")) {
            jdbc.update("""
                    update limited_benefit_ad_sessions
                       set status = 'CONSUMED', consumed_at = ?
                     where id = ? and status = 'VERIFIED'
                    """, Timestamp.from(now), adSessionId);
        }
        return result;
    }

    public LimitedBenefitAdSession createAdSession(
            UUID accountId,
            String offerCode,
            Instant now,
            LocalDate cycleDate) {
        if (offerCode == null || !offerCode.matches("^[a-z][a-z0-9_]{2,63}$")) {
            throw new IllegalArgumentException("Invalid limited benefit offer code");
        }
        lock("limited-benefit-account:" + accountId);
        jdbc.update("""
                update limited_benefit_ad_sessions
                   set status = 'EXPIRED'
                 where account_id = ? and status = 'PENDING' and expires_at <= ?
                """, accountId, Timestamp.from(now));
        Instant resetsAt = cycleDate.plusDays(1)
                .atStartOfDay(LimitedBenefitService.CAMPAIGN_ZONE).toInstant();
        LimitedBenefitCampaign campaign = findCurrent(accountId, now, cycleDate, resetsAt)
                .orElseThrow(() -> conflict("LIMITED_BENEFIT_NOT_ACTIVE",
                        "No limited benefit campaign is active."));
        LimitedBenefitOffer offer = campaign.offers().stream()
                .filter(value -> value.offerCode().equals(offerCode))
                .findFirst()
                .orElseThrow(() -> new LimitedBenefitException(
                        "LIMITED_BENEFIT_OFFER_NOT_FOUND", "Offer was not found."));
        if (!offer.fulfillmentType().equals("ADMOB_SSV")) {
            throw new LimitedBenefitException("LIMITED_BENEFIT_PROOF_REQUIRED",
                    "This offer is not fulfilled by AdMob.");
        }
        if (!offer.state().equals("AVAILABLE") || offer.productId() != null) {
            throw conflict("LIMITED_BENEFIT_OFFER_" + offer.state(),
                    "Offer cannot create an ad session in its current state.");
        }
        String adUnitId = jdbc.queryForObject(
                "select provider_key from limited_benefit_offers where id = ?",
                String.class, offer.id());
        if (adUnitId == null || adUnitId.isBlank()) {
            throw new LimitedBenefitException("LIMITED_BENEFIT_PROVIDER_UNAVAILABLE",
                    "AdMob ad unit is not configured.");
        }
        List<LimitedBenefitAdSession> existing = jdbc.query("""
                select id, ad_unit_id, status, expires_at
                  from limited_benefit_ad_sessions
                 where account_id = ? and offer_id = ? and cycle_date = ?
                   and status in ('PENDING', 'VERIFIED')
                   and (status = 'VERIFIED' or expires_at > ?)
                 order by case when status = 'VERIFIED' then 0 else 1 end,
                          created_at desc limit 1
                """, (rs, row) -> adSession(
                        rs.getObject("id", UUID.class), accountId,
                        rs.getString("ad_unit_id"),
                        rs.getString("status"),
                        rs.getTimestamp("expires_at").toInstant()),
                accountId, offer.id(), cycleDate, Timestamp.from(now));
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = now.plus(Duration.ofMinutes(10));
        jdbc.update("""
                insert into limited_benefit_ad_sessions(
                    id, account_id, campaign_version_id, offer_id, cycle_date,
                    status, ad_unit_id, expires_at)
                values (?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, sessionId, accountId, campaign.campaignVersionId(), offer.id(),
                cycleDate, adUnitId, Timestamp.from(expiresAt));
        return adSession(sessionId, accountId, adUnitId, "PENDING", expiresAt);
    }

    public AdMobRewardCallbackResult acceptAdMobCallback(
            AdMobSsvCallback callback,
            String expectedRewardItem,
            long expectedRewardAmount,
            Instant now) {
        lock("admob-transaction:" + callback.transactionId());
        List<StoredAdMobCallback> existing = jdbc.query("""
                select ad_session_id, raw_query, verified
                  from admob_reward_callbacks where transaction_id = ?
                """, (rs, row) -> new StoredAdMobCallback(
                        rs.getObject("ad_session_id", UUID.class),
                        rs.getString("raw_query"), rs.getBoolean("verified")),
                callback.transactionId());
        if (!existing.isEmpty()) {
            StoredAdMobCallback stored = existing.getFirst();
            if (stored.verified() && stored.rawQuery().equals(callback.rawQuery())
                    && stored.sessionId().equals(callback.sessionId())) {
                return new AdMobRewardCallbackResult(
                        stored.sessionId(), callback.transactionId(), true);
            }
            throw invalidProof();
        }

        List<AdSessionProof> sessions = jdbc.query("""
                select account_id, offer_id, cycle_date, status,
                       ad_unit_id, created_at, expires_at
                  from limited_benefit_ad_sessions
                 where id = ? for update
                """, (rs, row) -> new AdSessionProof(
                        rs.getObject("account_id", UUID.class),
                        rs.getObject("offer_id", UUID.class),
                        rs.getObject("cycle_date", LocalDate.class),
                        rs.getString("status"), rs.getString("ad_unit_id"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()), callback.sessionId());
        if (sessions.isEmpty()) {
            throw invalidProof();
        }
        AdSessionProof session = sessions.getFirst();
        if (!session.accountId().equals(callback.accountId())
                || !session.status().equals("PENDING")
                || !session.adUnitId().equals(callback.adUnitId())
                || !expectedRewardItem.equals(callback.rewardItem())
                || expectedRewardAmount != callback.rewardAmount()
                || callback.rewardedAt().isBefore(
                        session.createdAt().minusSeconds(5 * 60))
                || callback.rewardedAt().isAfter(session.expiresAt())
                || !now.isBefore(session.expiresAt())) {
            throw invalidProof();
        }
        jdbc.update("""
                insert into admob_reward_callbacks(
                    transaction_id, ad_session_id, raw_query, key_id,
                    verified, received_at)
                values (?, ?, ?, ?, true, ?)
                """, callback.transactionId(), callback.sessionId(),
                callback.rawQuery(), callback.keyId(), Timestamp.from(now));
        jdbc.update("""
                update limited_benefit_ad_sessions
                   set status = 'VERIFIED', verified_at = ?, transaction_id = ?
                 where id = ? and status = 'PENDING'
                """, Timestamp.from(now), callback.transactionId(), callback.sessionId());
        return new AdMobRewardCallbackResult(
                callback.sessionId(), callback.transactionId(), false);
    }

    private LimitedBenefitAdSession adSession(
            UUID sessionId, UUID accountId, String adUnitId,
            String status, Instant expiresAt) {
        return new LimitedBenefitAdSession(
                sessionId, sessionId.toString(), accountId.toString(),
                adUnitId, status, expiresAt);
    }

    private void validateAdSession(
            UUID accountId, UUID adSessionId, UUID offerId, LocalDate cycleDate) {
        if (adSessionId == null) {
            throw invalidProof();
        }
        List<AdSessionProof> rows = jdbc.query("""
                select s.account_id, s.offer_id, s.cycle_date, s.status,
                       s.ad_unit_id, s.created_at, s.expires_at
                  from limited_benefit_ad_sessions s
                 where s.id = ? for update
                """, (rs, row) -> new AdSessionProof(
                        rs.getObject("account_id", UUID.class),
                        rs.getObject("offer_id", UUID.class),
                        rs.getObject("cycle_date", LocalDate.class),
                        rs.getString("status"), rs.getString("ad_unit_id"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()), adSessionId);
        if (rows.isEmpty()) {
            throw invalidProof();
        }
        AdSessionProof proof = rows.getFirst();
        if (!proof.accountId().equals(accountId)
                || !proof.offerId().equals(offerId)
                || !proof.cycleDate().equals(cycleDate)
                || !proof.status().equals("VERIFIED")) {
            throw invalidProof();
        }
    }

    private void validateGoogleReceipt(
            UUID accountId,
            UUID receiptId,
            UUID expectedStoreOfferId,
            LocalDate cycleDate) {
        if (receiptId == null || expectedStoreOfferId == null) {
            throw invalidProof();
        }
        List<GoogleReceiptProof> rows = jdbc.query("""
                select r.account_id, r.state, r.fulfillment_type,
                       r.google_purchase_time, p.offer_id,
                       exists(select 1 from player_limited_benefit_claims c
                               where c.receipt_id = r.id) as consumed
                  from store_purchase_receipts r
                  join store_products p on p.id = r.product_id
                 where r.id = ?
                   for update of r
                """, (rs, row) -> new GoogleReceiptProof(
                        rs.getObject("account_id", UUID.class),
                        rs.getString("state"),
                        rs.getString("fulfillment_type"),
                        instant(rs.getTimestamp("google_purchase_time")),
                        rs.getObject("offer_id", UUID.class),
                        rs.getBoolean("consumed")), receiptId);
        if (rows.isEmpty()) {
            throw invalidProof();
        }
        GoogleReceiptProof proof = rows.getFirst();
        Instant cycleStart = cycleDate.atStartOfDay(
                LimitedBenefitService.CAMPAIGN_ZONE).toInstant();
        Instant cycleEnd = cycleDate.plusDays(1).atStartOfDay(
                LimitedBenefitService.CAMPAIGN_ZONE).toInstant();
        if (!proof.accountId().equals(accountId)
                || !proof.state().equals("GRANTED")
                || !proof.fulfillmentType().equals("LIMITED_BENEFIT")
                || !proof.storeOfferId().equals(expectedStoreOfferId)
                || proof.purchaseTime() == null
                || proof.purchaseTime().isBefore(cycleStart)
                || !proof.purchaseTime().isBefore(cycleEnd)
                || proof.consumed()) {
            throw invalidProof();
        }
    }

    private LimitedBenefitException invalidProof() {
        return new LimitedBenefitException(
                "LIMITED_BENEFIT_PROOF_INVALID",
                "Provider proof does not match this account, offer, or cycle.");
    }

    private Map<UUID, List<LimitedBenefitReward>> loadRewards(UUID campaignId) {
        Map<UUID, List<LimitedBenefitReward>> values = new LinkedHashMap<>();
        jdbc.query("""
                select offer_id, reward_type, reward_code, amount
                  from limited_benefit_offer_rewards
                 where campaign_version_id = ? order by offer_id, reward_order
                """, (RowCallbackHandler) rs -> values.computeIfAbsent(
                        rs.getObject("offer_id", UUID.class), ignored -> new ArrayList<>())
                        .add(new LimitedBenefitReward(
                                rs.getString("reward_type"), rs.getString("reward_code"),
                                rs.getLong("amount"))), campaignId);
        return values;
    }

    private boolean providerAvailable(OfferRow row) {
        return switch (row.fulfillmentType()) {
            case "FREE" -> true;
            case "GOOGLE_PLAY" -> row.productId() != null;
            case "ADMOB_SSV" -> row.providerKey() != null && !row.providerKey().isBlank();
            default -> false;
        };
    }

    private CampaignRow mapCampaign(ResultSet rs, int row) throws SQLException {
        return new CampaignRow(rs.getObject("id", UUID.class),
                rs.getString("campaign_code"), rs.getInt("version"));
    }

    private OfferRow mapOffer(ResultSet rs, int row) throws SQLException {
        return new OfferRow(rs.getObject("id", UUID.class), rs.getString("offer_code"),
                rs.getInt("display_order"), rs.getString("title"),
                rs.getString("fulfillment_type"), rs.getString("provider_key"),
                rs.getObject("store_offer_id", UUID.class),
                rs.getString("product_id"), rs.getBoolean("claimed"));
    }

    private StoredClaim mapStoredClaim(ResultSet rs, int row) throws SQLException {
        return new StoredClaim(rs.getObject("account_id", UUID.class),
                rs.getString("request_hash"), rs.getString("response_payload"));
    }

    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> null, key);
    }

    private String writeResult(LimitedBenefitClaimResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize limited benefit claim", exception);
        }
    }

    private LimitedBenefitClaimResult readResult(String payload) {
        try {
            return objectMapper.readValue(payload, LimitedBenefitClaimResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize limited benefit claim", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private LimitedBenefitException conflict(String code, String message) {
        return new LimitedBenefitException(code, message);
    }

    private record CampaignRow(UUID id, String code, int version) { }
    private record OfferRow(UUID id, String offerCode, int displayOrder, String title,
                            String fulfillmentType, String providerKey, UUID storeOfferId,
                            String productId, boolean claimed) { }
    private record StoredClaim(UUID accountId, String requestHash, String responsePayload) { }
    private record GoogleReceiptProof(
            UUID accountId, String state, String fulfillmentType,
            Instant purchaseTime, UUID storeOfferId, boolean consumed) { }
    private record AdSessionProof(
            UUID accountId, UUID offerId, LocalDate cycleDate,
            String status, String adUnitId, Instant createdAt,
            Instant expiresAt) { }
    private record StoredAdMobCallback(
            UUID sessionId, String rawQuery, boolean verified) { }
}
