package com.nayon.api.weeklygift;

import com.nayon.api.economy.EconomySnapshot;
import com.nayon.api.time.RewardPeriod;

import java.time.LocalDate;
import java.time.ZonedDateTime;

public record WeeklyGiftState(
        ZonedDateTime serverTime,
        String zoneId,
        LocalDate weekStart,
        ZonedDateTime nextResetAt,
        int loginDays,
        int requiredLoginDays,
        boolean claimable,
        boolean claimEnabled,
        boolean claimed,
        WeeklyGiftReward reward,
        EconomySnapshot economy,
        boolean replay) {

    public static WeeklyGiftState create(
            ZonedDateTime now,
            RewardPeriod period,
            int loginDays,
            boolean claimed,
            WeeklyGiftReward reward,
            EconomySnapshot economy,
            boolean replay) {
        int days = Math.min(7, Math.max(0, loginDays));
        boolean claimable = days >= 3 && !claimed;
        return new WeeklyGiftState(
                now, period.zoneId(), period.periodKey(), period.endsAt(),
                days, 3, claimable, claimable && reward != null,
                claimed, reward, economy, replay);
    }

    public WeeklyGiftState asReplay() {
        return replay ? this : new WeeklyGiftState(
                serverTime, zoneId, weekStart, nextResetAt, loginDays,
                requiredLoginDays, claimable, claimEnabled, claimed,
                reward, economy, true);
    }
}
