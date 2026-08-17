package com.nayon.api.limitedbenefit.admob;

import java.time.Instant;
import java.util.UUID;

public record LimitedBenefitAdSession(
        UUID sessionId,
        String customData,
        String userId,
        String adUnitId,
        String status,
        Instant expiresAt) {
}
