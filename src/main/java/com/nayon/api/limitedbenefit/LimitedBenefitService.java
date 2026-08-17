package com.nayon.api.limitedbenefit;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

@Service
public class LimitedBenefitService {
    static final ZoneId CAMPAIGN_ZONE = ZoneId.of("Asia/Seoul");

    private final JdbcLimitedBenefitRepository repository;
    private final Clock clock;

    @Autowired
    public LimitedBenefitService(JdbcLimitedBenefitRepository repository) {
        this(repository, Clock.systemUTC());
    }

    LimitedBenefitService(JdbcLimitedBenefitRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<LimitedBenefitCampaign> current(UUID accountId) {
        Instant now = clock.instant();
        LocalDate cycleDate = now.atZone(CAMPAIGN_ZONE).toLocalDate();
        Instant resetsAt = cycleDate.plusDays(1).atStartOfDay(CAMPAIGN_ZONE).toInstant();
        return repository.findCurrent(accountId, now, cycleDate, resetsAt);
    }

    @Transactional
    public LimitedBenefitClaimResult claimFree(
            UUID accountId, UUID requestId, String offerCode) {
        Instant now = clock.instant();
        LocalDate cycleDate = now.atZone(CAMPAIGN_ZONE).toLocalDate();
        return repository.claimFree(accountId, requestId, offerCode, now, cycleDate);
    }
}
