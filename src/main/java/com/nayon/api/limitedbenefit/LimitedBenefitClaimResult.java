package com.nayon.api.limitedbenefit;

import com.nayon.api.economy.EconomySnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LimitedBenefitClaimResult(
        UUID claimId,
        String offerCode,
        LocalDate cycleDate,
        List<LimitedBenefitReward> rewards,
        EconomySnapshot economy,
        boolean replay) {

    public LimitedBenefitClaimResult {
        rewards = List.copyOf(rewards);
    }

    public LimitedBenefitClaimResult asReplay() {
        return replay ? this : new LimitedBenefitClaimResult(
                claimId, offerCode, cycleDate, rewards, economy, true);
    }
}
