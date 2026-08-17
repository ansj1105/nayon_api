package com.nayon.api.limitedbenefit.admob;

import java.time.Instant;
import java.util.UUID;

public record AdMobSsvCallback(
        String rawQuery,
        long keyId,
        String adUnitId,
        UUID sessionId,
        long rewardAmount,
        String rewardItem,
        Instant rewardedAt,
        String transactionId,
        UUID accountId) {
}
