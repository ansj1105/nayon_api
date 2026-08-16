package com.nayon.api.share;

import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ShareRewardService {

    public static final String REWARD_ASSET_CODE = "DIAMOND";
    public static final long REWARD_AMOUNT = 50L;

    private final ShareRewardRepository shareRepository;
    private final EconomyRepository economyRepository;

    public ShareRewardService(
            ShareRewardRepository shareRepository,
            EconomyRepository economyRepository) {
        this.shareRepository = shareRepository;
        this.economyRepository = economyRepository;
    }

    @Transactional(readOnly = true)
    public ShareRewardResult get(UUID accountId) {
        ShareRewardState state = shareRepository.findByAccountId(accountId)
                .orElseGet(() -> ShareRewardState.initial(accountId));
        return new ShareRewardResult(state, economyRepository.findSnapshot(accountId));
    }

    @Transactional
    public ShareRewardResult markOpened(UUID accountId, String target) {
        String normalizedTarget = normalizeTarget(target);
        ShareRewardState state = shareRepository.markOpened(accountId, normalizedTarget);
        return new ShareRewardResult(state, economyRepository.findSnapshot(accountId));
    }

    @Transactional
    public ShareRewardResult claim(UUID accountId, UUID requestId) {
        ShareRewardState state = shareRepository.lockOrCreate(accountId);
        if (!state.shared()) {
            throw new ShareRequiredException();
        }

        EconomySnapshot economy = economyRepository.findSnapshot(accountId);
        if (!economy.bootstrapped()) {
            throw new EconomyNotBootstrappedForShareException();
        }
        if (state.rewardClaimed()) {
            return new ShareRewardResult(state, economy);
        }

        economy = economyRepository.creditCurrency(
                accountId,
                requestId,
                REWARD_ASSET_CODE,
                REWARD_AMOUNT,
                "SHARE_REWARD",
                "PLAYER_SHARE_REWARD",
                state.id());
        state = shareRepository.markClaimed(accountId);
        return new ShareRewardResult(state, economy);
    }

    private String normalizeTarget(String target) {
        if (target == null) {
            return null;
        }
        String value = target.trim();
        if (value.isEmpty() || value.length() > 255) {
            throw new IllegalArgumentException("Invalid share target");
        }
        return value;
    }
}
