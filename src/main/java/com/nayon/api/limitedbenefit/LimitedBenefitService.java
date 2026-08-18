package com.nayon.api.limitedbenefit;

import com.nayon.api.time.KstGameTimeCalculator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Service
public class LimitedBenefitService {
    private final JdbcLimitedBenefitRepository repository;
    private final KstGameTimeCalculator time;

    @Autowired
    public LimitedBenefitService(
            JdbcLimitedBenefitRepository repository,
            KstGameTimeCalculator time) {
        this.repository = repository;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public Optional<LimitedBenefitCampaign> current(UUID accountId) {
        Instant now = time.now().toInstant();
        LocalDate cycleDate = time.dailyPeriod().periodKey();
        Instant resetsAt = time.dailyPeriod().endsAt().toInstant();
        return repository.findCurrent(accountId, now, cycleDate, resetsAt);
    }

    @Transactional
    public LimitedBenefitClaimResult claimFree(
            UUID accountId, UUID requestId, String offerCode) {
        return claim(accountId, requestId, offerCode, null, null);
    }

    @Transactional
    public LimitedBenefitClaimResult claim(
            UUID accountId,
            UUID requestId,
            String offerCode,
            UUID receiptId,
            UUID adSessionId) {
        Instant now = time.now().toInstant();
        LocalDate cycleDate = time.dailyPeriod().periodKey();
        return repository.claim(
                accountId, requestId, offerCode, receiptId, adSessionId,
                now, cycleDate);
    }
}
