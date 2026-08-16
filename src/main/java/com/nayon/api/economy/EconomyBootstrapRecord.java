package com.nayon.api.economy;

import java.time.Instant;
import java.util.UUID;

public record EconomyBootstrapRecord(
        UUID accountId,
        UUID requestId,
        String requestHash,
        EconomySnapshot snapshot,
        Instant createdAt) {
}
