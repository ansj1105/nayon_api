package com.nayon.api.subscription;

import java.time.Instant;
import java.util.UUID;

public record PlayerSubscription(
        UUID id,
        UUID accountId,
        SubscriptionPlanCode planCode,
        SubscriptionState state,
        Instant startedAt,
        Instant expiresAt,
        boolean autoRenewing,
        Instant lastVerifiedAt,
        boolean replay) {

    public static PlayerSubscription snapshot(
            UUID id,
            UUID accountId,
            SubscriptionPlanCode planCode,
            SubscriptionState state,
            Instant startedAt,
            Instant expiresAt,
            boolean autoRenewing,
            Instant lastVerifiedAt,
            boolean replay) {
        return new PlayerSubscription(id, accountId, planCode, state,
                startedAt, expiresAt, autoRenewing, lastVerifiedAt, replay);
    }

    public boolean entitled(Instant at) {
        if (expiresAt == null || !at.isBefore(expiresAt)) {
            return false;
        }
        return state == SubscriptionState.ACTIVE
                || state == SubscriptionState.CANCELED
                || state == SubscriptionState.GRACE_PERIOD;
    }

    public PlayerSubscription asReplay() {
        return replay ? this : new PlayerSubscription(
                id, accountId, planCode, state, startedAt, expiresAt,
                autoRenewing, lastVerifiedAt, true);
    }
}
