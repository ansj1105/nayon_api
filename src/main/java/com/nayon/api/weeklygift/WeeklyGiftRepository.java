package com.nayon.api.weeklygift;

import com.nayon.api.time.RewardPeriod;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface WeeklyGiftRepository {
    WeeklyGiftState get(UUID accountId, RewardPeriod period, Instant now);

    WeeklyGiftState checkIn(
            UUID accountId, RewardPeriod period, LocalDate loginDate, Instant now);

    WeeklyGiftState claim(
            UUID accountId, UUID requestId, RewardPeriod period, Instant now);
}
