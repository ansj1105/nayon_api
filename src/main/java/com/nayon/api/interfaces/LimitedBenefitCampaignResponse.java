package com.nayon.api.interfaces;

import com.nayon.api.limitedbenefit.LimitedBenefitCampaign;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LimitedBenefitCampaignResponse(
        UUID campaignVersionId,
        String campaignCode,
        int version,
        Instant serverTime,
        LocalDate cycleDate,
        Instant resetsAt,
        List<Offer> offers) {

    static LimitedBenefitCampaignResponse from(LimitedBenefitCampaign campaign) {
        return new LimitedBenefitCampaignResponse(
                campaign.campaignVersionId(), campaign.campaignCode(), campaign.version(),
                campaign.serverTime(), campaign.cycleDate(), campaign.resetsAt(),
                campaign.offers().stream().map(offer -> new Offer(
                        offer.offerCode(), offer.displayOrder(), offer.title(),
                        offer.fulfillmentType(), offer.productId(), offer.state(),
                        offer.rewards().stream().map(reward -> new Reward(
                                reward.type(), reward.code(), reward.amount())).toList()))
                        .toList());
    }

    public record Offer(
            String offerCode,
            int displayOrder,
            String title,
            String fulfillmentType,
            String productId,
            String state,
            List<Reward> rewards) { }

    public record Reward(String type, String code, long amount) { }
}
