package com.nayon.api.korion;

import java.time.Instant;
import java.util.UUID;

public record KorionWalletLink(
        UUID accountId,
        String address,
        UUID verifiedRequestId,
        Instant verifiedAt) {
}
