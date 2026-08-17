package com.nayon.api.limitedbenefit;

import java.util.List;
import java.util.UUID;

public record LimitedBenefitOffer(
        UUID id,
        String offerCode,
        int displayOrder,
        String title,
        String fulfillmentType,
        String productId,
        String state,
        List<LimitedBenefitReward> rewards) {

    public LimitedBenefitOffer {
        rewards = List.copyOf(rewards);
    }
}
