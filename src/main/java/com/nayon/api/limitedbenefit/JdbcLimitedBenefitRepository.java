package com.nayon.api.limitedbenefit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
                       o.fulfillment_type, o.provider_key,
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
                    row.fulfillmentType(), row.productId(), state,
                    rewards.getOrDefault(row.id(), List.of())));
            predecessorClaimed = row.claimed();
        }
        return Optional.of(new LimitedBenefitCampaign(
                campaign.id(), campaign.code(), campaign.version(), now,
                cycleDate, resetsAt, offers));
    }

    public LimitedBenefitClaimResult claimFree(
            UUID accountId,
            UUID requestId,
            String offerCode,
            Instant now,
            LocalDate cycleDate) {
        if (offerCode == null || !offerCode.matches("^[a-z][a-z0-9_]{2,63}$")) {
            throw new IllegalArgumentException("Invalid limited benefit offer code");
        }
        lock("limited-benefit-account:" + accountId);
        lock("limited-benefit-request:" + requestId);
        String requestHash = sha256(offerCode + "\nFREE");

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
        if (!offer.fulfillmentType().equals("FREE")) {
            throw new LimitedBenefitException("LIMITED_BENEFIT_PROOF_REQUIRED",
                    "This offer requires provider proof.");
        }
        if (!offer.state().equals("AVAILABLE")) {
            throw conflict("LIMITED_BENEFIT_OFFER_" + offer.state(),
                    "Offer cannot be claimed in its current state.");
        }

        UUID claimId = UUID.randomUUID();
        for (LimitedBenefitReward reward : offer.rewards()) {
            if (reward.type().equals("CURRENCY")) {
                economy = economyRepository.creditCurrency(
                        accountId, requestId, reward.code(), reward.amount(),
                        "LIMITED_BENEFIT", "PLAYER_LIMITED_BENEFIT_CLAIM", claimId);
            } else if (reward.type().equals("ITEM")) {
                economy = economyRepository.creditItem(
                        accountId, requestId, reward.code(), reward.amount(),
                        "LIMITED_BENEFIT", "PLAYER_LIMITED_BENEFIT_CLAIM", claimId);
            } else {
                throw new LimitedBenefitException("LIMITED_BENEFIT_REWARD_UNSUPPORTED",
                        "Equipment boxes require a verified purchase flow.");
            }
        }
        LimitedBenefitClaimResult result = new LimitedBenefitClaimResult(
                claimId, offerCode, cycleDate, offer.rewards(), economy, false);
        jdbc.update("""
                insert into player_limited_benefit_claims(
                    id, account_id, campaign_version_id, offer_id, cycle_date,
                    request_id, request_hash, proof_type, response_payload, claimed_at)
                values (?, ?, ?, ?, ?, ?, ?, 'FREE', ?::jsonb, ?)
                """, claimId, accountId, campaign.campaignVersionId(), offer.id(),
                cycleDate, requestId, requestHash, writeResult(result), Timestamp.from(now));
        return result;
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

    private LimitedBenefitException conflict(String code, String message) {
        return new LimitedBenefitException(code, message);
    }

    private record CampaignRow(UUID id, String code, int version) { }
    private record OfferRow(UUID id, String offerCode, int displayOrder, String title,
                            String fulfillmentType, String providerKey, String productId,
                            boolean claimed) { }
    private record StoredClaim(UUID accountId, String requestHash, String responsePayload) { }
}
