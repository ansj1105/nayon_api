package com.nayon.api.share;

import java.util.Optional;
import java.util.UUID;

public interface ShareRewardRepository {
    Optional<ShareRewardState> findByAccountId(UUID accountId);

    ShareRewardState markOpened(UUID accountId, String target);

    ShareRewardState lockOrCreate(UUID accountId);

    ShareRewardState markClaimed(UUID accountId);
}
