package com.nayon.api.levelreward;

import com.nayon.api.progression.AccountLevelCatalog;
import com.nayon.api.subscription.PlayerSubscription;
import com.nayon.api.subscription.SubscriptionService;
import com.nayon.api.time.ServerClock;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class LevelRewardService {

    private final LevelRewardRepository repository;
    private final SubscriptionService subscriptions;
    private final AccountLevelCatalog levels;
    private final ServerClock clock;

    @Autowired
    public LevelRewardService(
            LevelRewardRepository repository,
            SubscriptionService subscriptions,
            AccountLevelCatalog levels,
            ServerClock clock) {
        this.repository = repository;
        this.subscriptions = subscriptions;
        this.levels = levels;
        this.clock = clock;
    }

    LevelRewardService(
            LevelRewardRepository repository,
            SubscriptionService subscriptions,
            AccountLevelCatalog levels,
            Clock clock) {
        this(repository, subscriptions, levels, new ServerClock(clock));
    }

    public LevelRewardList get(UUID accountId) {
        Instant now = clock.now();
        long total = repository.totalAccountExp(accountId);
        int level = levels.level(total);
        List<PlayerSubscription> current = subscriptions.findAll(accountId);
        return new LevelRewardList(level, total, current,
                repository.findAll(accountId, level, current, now));
    }

    public LevelRewardClaimResult claim(
            UUID accountId,
            UUID requestId,
            LevelRewardTrackCode trackCode,
            int requiredLevel) {
        if (requiredLevel < 1 || requiredLevel > 50) {
            throw new IllegalArgumentException("Invalid level reward threshold");
        }
        long total = repository.totalAccountExp(accountId);
        return repository.claim(accountId, requestId,
                hash(trackCode.name() + "\n" + requiredLevel),
                trackCode, requiredLevel, levels.level(total), clock.now());
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
