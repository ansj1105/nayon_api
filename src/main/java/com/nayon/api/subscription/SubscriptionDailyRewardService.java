package com.nayon.api.subscription;

import com.nayon.api.time.KstGameTimeCalculator;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class SubscriptionDailyRewardService {

    private final SubscriptionRewardRepository repository;
    private final KstGameTimeCalculator time;

    public SubscriptionDailyRewardService(
            SubscriptionRewardRepository repository,
            KstGameTimeCalculator time) {
        this.repository = repository;
        this.time = time;
    }

    public SubscriptionDailyRewardResult claim(
            UUID accountId,
            UUID requestId,
            SubscriptionPlanCode planCode) {
        Instant now = time.now().toInstant();
        LocalDate date = time.dailyPeriod().periodKey();
        return repository.claimDaily(
                accountId, requestId, hash(planCode.name() + "\n" + date),
                planCode, date, now);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
