package com.nayon.api.store;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface FirstPurchaseRewardRepository {
    Optional<FirstPurchaseReward> findByAccount(UUID accountId);

    Optional<FirstPurchaseReward> findByReceipt(UUID receiptId);

    FirstPurchaseReward grantIfAbsent(
            UUID accountId,
            UUID receiptId,
            UUID requestId,
            Instant purchaseTime);
}
