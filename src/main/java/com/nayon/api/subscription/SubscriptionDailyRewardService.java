package com.nayon.api.subscription;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class SubscriptionDailyRewardService {

    private final SubscriptionRewardRepository repository;
    private final Clock clock;

    @Autowired
    public SubscriptionDailyRewardService(SubscriptionRewardRepository repository) {
        this(repository, Clock.systemUTC());
    }

    SubscriptionDailyRewardService(
            SubscriptionRewardRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public SubscriptionDailyRewardResult claim(
            UUID accountId,
            UUID requestId,
            SubscriptionPlanCode planCode) {
        Instant now = Instant.now(clock);
        LocalDate date = LocalDate.ofInstant(now, ZoneOffset.UTC);
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
