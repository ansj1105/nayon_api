package com.nayon.api.interfaces;

import com.nayon.api.limitedbenefit.admob.LimitedBenefitAdSession;

import java.time.Instant;
import java.util.UUID;

public record LimitedBenefitAdSessionResponse(
        UUID sessionId,
        String customData,
        String userId,
        String adUnitId,
        String status,
        Instant expiresAt) {

    static LimitedBenefitAdSessionResponse from(LimitedBenefitAdSession session) {
        return new LimitedBenefitAdSessionResponse(
                session.sessionId(), session.customData(), session.userId(),
                session.adUnitId(), session.status(), session.expiresAt());
    }
}
