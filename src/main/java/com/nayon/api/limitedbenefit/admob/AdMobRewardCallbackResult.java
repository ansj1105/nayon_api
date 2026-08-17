package com.nayon.api.limitedbenefit.admob;

import java.util.UUID;

public record AdMobRewardCallbackResult(
        UUID sessionId,
        String transactionId,
        boolean replay) {
}
