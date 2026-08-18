package com.nayon.api.interfaces;

import com.nayon.api.weeklygift.WeeklyGiftState;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record WeeklyGiftResponse(
        OffsetDateTime serverTime,
        String zoneId,
        LocalDate weekStart,
        OffsetDateTime nextResetAt,
        int loginDays,
        int requiredLoginDays,
        boolean claimable,
        boolean claimEnabled,
        boolean claimed,
        Reward reward,
        EconomyResponse economy) {

    static WeeklyGiftResponse from(WeeklyGiftState state) {
        return new WeeklyGiftResponse(
                state.serverTime().toOffsetDateTime(), state.zoneId(),
                state.weekStart(), state.nextResetAt().toOffsetDateTime(),
                state.loginDays(), state.requiredLoginDays(), state.claimable(),
                state.claimEnabled(), state.claimed(),
                state.reward() == null ? null : new Reward(
                        state.reward().assetType(), state.reward().assetCode(),
                        state.reward().amount()),
                state.economy() == null ? null : EconomyResponse.from(state.economy()));
    }

    public record Reward(String assetType, String assetCode, long amount) {
    }
}
