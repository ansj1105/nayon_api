package com.nayon.api.share;

import java.time.Instant;
import java.util.UUID;

public record ShareRewardState(
        UUID accountId,
        UUID id,
        boolean shared,
        boolean rewardClaimed,
        Instant sharedAt,
        Instant rewardClaimedAt,
        String shareTarget) {

    public static ShareRewardState initial(UUID accountId) {
        return new ShareRewardState(accountId, null, false, false, null, null, null);
    }

    public static ShareRewardState initialPersisted(UUID accountId) {
        return new ShareRewardState(
                accountId, UUID.randomUUID(), false, false, null, null, null);
    }

    public static ShareRewardState opened(UUID accountId, UUID id, String target) {
        return new ShareRewardState(
                accountId, id, true, false, Instant.now(), null, target);
    }

    public ShareRewardState open(String target) {
        if (shared) {
            return this;
        }
        return new ShareRewardState(
                accountId, id, true, false, Instant.now(), null, target);
    }

    public ShareRewardState claim() {
        return new ShareRewardState(
                accountId, id, true, true, sharedAt, Instant.now(), shareTarget);
    }
}
