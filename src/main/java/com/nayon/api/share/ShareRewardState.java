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

    public static ShareRewardState opened(
            UUID accountId, UUID id, String target, Instant now) {
        return new ShareRewardState(
                accountId, id, true, false, now, null, target);
    }

    public ShareRewardState open(String target, Instant now) {
        if (shared) {
            return this;
        }
        return new ShareRewardState(
                accountId, id, true, false, now, null, target);
    }

    public ShareRewardState claim(Instant now) {
        return new ShareRewardState(
                accountId, id, true, true, sharedAt, now, shareTarget);
    }
}
