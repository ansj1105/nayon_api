package com.nayon.api.limitedbenefit.admob;

import com.nayon.api.limitedbenefit.JdbcLimitedBenefitRepository;
import com.nayon.api.time.KstGameTimeCalculator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class AdMobRewardService {
    private final JdbcLimitedBenefitRepository repository;
    private final AdMobSsvVerifier verifier;
    private final String rewardItem;
    private final long rewardAmount;
    private final KstGameTimeCalculator time;

    @Autowired
    public AdMobRewardService(
            JdbcLimitedBenefitRepository repository,
            AdMobSsvVerifier verifier,
            @Value("${nayon.limited-benefit.admob.reward-item}") String rewardItem,
            @Value("${nayon.limited-benefit.admob.reward-amount}") long rewardAmount,
            KstGameTimeCalculator time) {
        this.repository = repository;
        this.verifier = verifier;
        this.rewardItem = rewardItem;
        this.rewardAmount = rewardAmount;
        this.time = time;
    }

    @Transactional
    public LimitedBenefitAdSession createSession(UUID accountId, String offerCode) {
        Instant now = time.now().toInstant();
        LocalDate cycleDate = time.dailyPeriod().periodKey();
        return repository.createAdSession(accountId, offerCode, now, cycleDate);
    }

    @Transactional
    public AdMobRewardCallbackResult accept(String rawQuery) {
        return acceptVerified(verifier.verify(rawQuery));
    }

    @Transactional
    public AdMobRewardCallbackResult acceptVerified(AdMobSsvCallback callback) {
        return repository.acceptAdMobCallback(
                callback, rewardItem, rewardAmount, time.now().toInstant());
    }
}
