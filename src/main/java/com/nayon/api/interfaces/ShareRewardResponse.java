package com.nayon.api.interfaces;

import com.nayon.api.share.ShareRewardResult;
import com.nayon.api.share.ShareRewardService;

public record ShareRewardResponse(
        boolean shared,
        boolean rewardClaimed,
        boolean canShare,
        boolean canClaim,
        Reward reward,
        EconomyResponse economy) {

    static ShareRewardResponse from(ShareRewardResult result) {
        boolean shared = result.state().shared();
        boolean claimed = result.state().rewardClaimed();
        return new ShareRewardResponse(
                shared,
                claimed,
                !shared,
                shared && !claimed,
                new Reward(
                        ShareRewardService.REWARD_ASSET_CODE,
                        ShareRewardService.REWARD_AMOUNT),
                EconomyResponse.from(result.economy()));
    }

    public record Reward(String assetCode, long amount) {
    }
}
