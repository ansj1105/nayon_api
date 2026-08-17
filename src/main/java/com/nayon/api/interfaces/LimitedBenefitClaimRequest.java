package com.nayon.api.interfaces;

import java.util.UUID;

public record LimitedBenefitClaimRequest(UUID receiptId, UUID adSessionId) {
}
