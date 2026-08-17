package com.nayon.api.interfaces;

import com.nayon.api.limitedbenefit.LimitedBenefitClaimResult;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LimitedBenefitClaimResponse(
        UUID claimId,
        String offerCode,
        LocalDate cycleDate,
        List<LimitedBenefitCampaignResponse.Reward> rewards,
        EconomyResponse economy,
        boolean replay) {

    static LimitedBenefitClaimResponse from(LimitedBenefitClaimResult result) {
        return new LimitedBenefitClaimResponse(
                result.claimId(), result.offerCode(), result.cycleDate(),
                result.rewards().stream().map(reward ->
                        new LimitedBenefitCampaignResponse.Reward(
                                reward.type(), reward.code(), reward.amount())).toList(),
                EconomyResponse.from(result.economy()), result.replay());
    }
}
