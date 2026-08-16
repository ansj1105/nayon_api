package com.nayon.api.accountlink;

import com.nayon.api.economy.EconomySnapshot;

public record AccountLinkRewardResult(
        AccountLinkRewardState state,
        boolean canClaim,
        EconomySnapshot economy) {
}
