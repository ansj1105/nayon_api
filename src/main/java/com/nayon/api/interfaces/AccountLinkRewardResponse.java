package com.nayon.api.interfaces;

import com.nayon.api.accountlink.AccountLinkRewardResult;

import java.util.Map;

public record AccountLinkRewardResponse(
        boolean rewardClaimed,
        boolean canClaim,
        Map<String, Long> reward,
        EconomyResponse economy) {

    public static AccountLinkRewardResponse from(AccountLinkRewardResult result) {
        return new AccountLinkRewardResponse(
                result.state().rewardClaimed(), result.canClaim(),
                Map.of("DIAMOND", 300L, "SILVER_KEY", 1L, "GOLD_KEY", 1L),
                EconomyResponse.from(result.economy()));
    }
}
