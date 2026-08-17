package com.nayon.api.limitedbenefit.admob;

import com.nayon.api.limitedbenefit.JdbcLimitedBenefitRepository;
import com.nayon.api.limitedbenefit.LimitedBenefitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@Service
public class AdMobRewardService {
    private static final ZoneId CAMPAIGN_ZONE = ZoneId.of("Asia/Seoul");

    private final JdbcLimitedBenefitRepository repository;
    private final AdMobSsvVerifier verifier;
    private final String rewardItem;
    private final long rewardAmount;
    private final Clock clock;

    @Autowired
    public AdMobRewardService(
            JdbcLimitedBenefitRepository repository,
            AdMobSsvVerifier verifier,
            @Value("${nayon.limited-benefit.admob.reward-item}") String rewardItem,
            @Value("${nayon.limited-benefit.admob.reward-amount}") long rewardAmount) {
        this(repository, verifier, rewardItem, rewardAmount, Clock.systemUTC());
    }

    AdMobRewardService(
            JdbcLimitedBenefitRepository repository,
            AdMobSsvVerifier verifier,
            String rewardItem,
            long rewardAmount,
            Clock clock) {
        this.repository = repository;
        this.verifier = verifier;
        this.rewardItem = rewardItem;
        this.rewardAmount = rewardAmount;
        this.clock = clock;
    }

    @Transactional
    public LimitedBenefitAdSession createSession(UUID accountId, String offerCode) {
        Instant now = clock.instant();
        LocalDate cycleDate = now.atZone(CAMPAIGN_ZONE).toLocalDate();
        return repository.createAdSession(accountId, offerCode, now, cycleDate);
    }

    @Transactional
    public AdMobRewardCallbackResult accept(String rawQuery) {
        return acceptVerified(verifier.verify(rawQuery));
    }

    @Transactional
    public AdMobRewardCallbackResult acceptVerified(AdMobSsvCallback callback) {
        return repository.acceptAdMobCallback(
                callback, rewardItem, rewardAmount, clock.instant());
    }
}
