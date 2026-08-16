package com.nayon.api.battle.offline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.battle.BattleStageCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class OfflineBattleService {
    private static final long WINDOW_SECONDS = 24 * 60 * 60;

    private final OfflineBattleRepository repository;
    private final OfflineBattleEvaluator evaluator;
    private final BattleStageCatalog catalog;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public OfflineBattleService(
            OfflineBattleRepository repository,
            OfflineBattleEvaluator evaluator,
            BattleStageCatalog catalog,
            ObjectMapper objectMapper) {
        this(repository, evaluator, catalog, objectMapper, Clock.systemUTC());
    }

    OfflineBattleService(
            OfflineBattleRepository repository,
            OfflineBattleEvaluator evaluator,
            BattleStageCatalog catalog,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.evaluator = evaluator;
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public OfflineBattleWindowResult sync(UUID accountId, UUID requestId) {
        var now = clock.instant();
        return repository.sync(
                accountId, requestId, hash("offline-window-v1"),
                now, now.plusSeconds(WINDOW_SECONDS), catalog.configuration());
    }

    @Transactional
    public OfflineBattleSubmissionResult submit(
            UUID accountId,
            UUID requestId,
            OfflineBattleSubmissionCommand command) {
        validate(command);
        return repository.submit(
                accountId, requestId, hash(command), command,
                evaluator, clock.instant());
    }

    private void validate(OfflineBattleSubmissionCommand command) {
        if (command == null || command.windowId() == null
                || command.runs() == null || command.runs().isEmpty()
                || command.runs().size() > 20) {
            throw new IllegalArgumentException("Invalid offline battle submission");
        }
        HashSet<UUID> ids = new HashSet<>();
        for (OfflineBattleRunCommand run : command.runs()) {
            if (run == null || run.runId() == null || !ids.add(run.runId())
                    || run.stageCode() == null || run.stageCode().isBlank()
                    || run.stageCode().length() > 80 || run.outcome() == null
                    || run.elapsedSeconds() < 0 || run.elapsedSeconds() > 86400
                    || run.killCount() < 0 || run.totalDamage() == null
                    || run.totalDamage().signum() < 0
                    || run.totalDamage().precision() > 24
                    || run.totalDamage().scale() > 4
                    || run.reachedWave() < 0) {
                throw new IllegalArgumentException("Invalid offline battle run");
            }
        }
    }

    private String hash(Object value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot hash offline battle command", exception);
        }
    }
}
