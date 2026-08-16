package com.nayon.api.share;

import com.nayon.api.economy.EconomySnapshot;

public record ShareRewardResult(
        ShareRewardState state,
        EconomySnapshot economy) {
}
