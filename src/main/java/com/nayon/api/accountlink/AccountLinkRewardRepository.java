package com.nayon.api.accountlink;

import java.util.Optional;
import java.util.UUID;

public interface AccountLinkRewardRepository {
    Optional<AccountLinkRewardState> find(UUID accountId);
    AccountLinkRewardState lockOrCreate(UUID accountId);
    boolean hasGoogleIdentity(UUID accountId);
    boolean hasKorionWalletLink(UUID accountId);
    AccountLinkRewardState markClaimed(UUID accountId);
}
