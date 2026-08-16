package com.nayon.api.accountlink;

import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountLinkRewardService {
    public static final long DIAMOND_AMOUNT = 300L;
    public static final long SILVER_KEY_AMOUNT = 1L;
    public static final long GOLD_KEY_AMOUNT = 1L;
    private static final String REASON = "ACCOUNT_LINK_REWARD";
    private static final String REFERENCE = "PLAYER_ACCOUNT_LINK_REWARD";

    private final AccountLinkRewardRepository repository;
    private final EconomyRepository economyRepository;

    public AccountLinkRewardService(
            AccountLinkRewardRepository repository,
            EconomyRepository economyRepository) {
        this.repository = repository;
        this.economyRepository = economyRepository;
    }

    @Transactional(readOnly = true)
    public AccountLinkRewardResult get(UUID accountId) {
        AccountLinkRewardState state = repository.find(accountId)
                .orElseGet(() -> AccountLinkRewardState.initial(accountId));
        boolean canClaim = !state.rewardClaimed()
                && repository.hasGoogleIdentity(accountId)
                && repository.hasKorionWalletLink(accountId);
        return new AccountLinkRewardResult(
                state, canClaim, economyRepository.findSnapshot(accountId));
    }

    @Transactional
    public AccountLinkRewardResult claim(UUID accountId, UUID requestId) {
        AccountLinkRewardState state = repository.lockOrCreate(accountId);
        EconomySnapshot economy = economyRepository.findSnapshot(accountId);
        if (!economy.bootstrapped()) {
            throw new AccountLinkRewardException(
                    "ECONOMY_NOT_BOOTSTRAPPED",
                    "The account economy must be bootstrapped before claiming the reward.");
        }
        if (state.rewardClaimed()) {
            return new AccountLinkRewardResult(state, false, economy);
        }
        if (!repository.hasGoogleIdentity(accountId)) {
            throw new AccountLinkRewardException(
                    "GOOGLE_LINK_REQUIRED", "A Google account link is required.");
        }
        if (!repository.hasKorionWalletLink(accountId)) {
            throw new AccountLinkRewardException(
                    "KORION_LINK_REQUIRED", "A verified KORION wallet link is required.");
        }

        economyRepository.creditCurrency(accountId, requestId, "DIAMOND", DIAMOND_AMOUNT,
                REASON, REFERENCE, state.id());
        economyRepository.creditItem(accountId, requestId, "SILVER_KEY", SILVER_KEY_AMOUNT,
                REASON, REFERENCE, state.id());
        economy = economyRepository.creditItem(accountId, requestId, "GOLD_KEY", GOLD_KEY_AMOUNT,
                REASON, REFERENCE, state.id());
        state = repository.markClaimed(accountId);
        return new AccountLinkRewardResult(state, false, economy);
    }
}
