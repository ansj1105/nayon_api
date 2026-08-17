package com.nayon.api.limitedbenefit;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LimitedBenefitCampaign(
        UUID campaignVersionId,
        String campaignCode,
        int version,
        Instant serverTime,
        LocalDate cycleDate,
        Instant resetsAt,
        List<LimitedBenefitOffer> offers) {

    public LimitedBenefitCampaign {
        offers = List.copyOf(offers);
    }
}
