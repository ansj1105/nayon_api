package com.nayon.api.accountlink;

import java.time.Instant;
import java.util.UUID;

public record AccountLinkRewardState(
        UUID accountId,
        UUID id,
        boolean rewardClaimed,
        Instant rewardClaimedAt) {
    public static AccountLinkRewardState initial(UUID accountId) {
        return new AccountLinkRewardState(accountId, null, false, null);
    }
}
