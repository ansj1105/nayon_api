package com.nayon.api.weeklygift;

import com.nayon.api.time.KstGameTimeCalculator;
import com.nayon.api.time.RewardPeriod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.UUID;

@Service
public class WeeklyGiftService {
    private final WeeklyGiftRepository repository;
    private final KstGameTimeCalculator time;

    public WeeklyGiftService(
            WeeklyGiftRepository repository,
            KstGameTimeCalculator time) {
        this.repository = repository;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public WeeklyGiftState get(UUID accountId) {
        ZonedDateTime now = time.now();
        return repository.get(accountId, time.weeklyPeriod(), now.toInstant());
    }

    @Transactional
    public WeeklyGiftState checkIn(UUID accountId) {
        ZonedDateTime now = time.now();
        RewardPeriod period = time.weeklyPeriod();
        return repository.checkIn(
                accountId, period, now.toLocalDate(), now.toInstant());
    }

    @Transactional
    public WeeklyGiftState claim(UUID accountId, UUID requestId) {
        ZonedDateTime now = time.now();
        return repository.claim(
                accountId, requestId, time.weeklyPeriod(), now.toInstant());
    }
}
