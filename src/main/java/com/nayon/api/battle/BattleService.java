package com.nayon.api.battle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.time.ServerClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class BattleService {
    private final BattleRepository repository;
    private final BattleStageCatalog catalog;
    private final BattleAnomalyEvaluator evaluator;
    private final ObjectMapper objectMapper;
    private final ServerClock clock;

    @Autowired
    public BattleService(
            BattleRepository repository,
            BattleStageCatalog catalog,
            BattleAnomalyEvaluator evaluator,
            ObjectMapper objectMapper,
            ServerClock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.evaluator = evaluator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public BattleSessionResult start(
            UUID accountId, UUID requestId, BattleStartCommand command) {
        validateStart(command);
        BattleStageLimit stage = catalog.require(command.stageCode());
        return repository.start(
                accountId, requestId, hash(command), command, stage,
                catalog.configuration(), clock.now());
    }

    @Transactional
    public BattleCompletionResult complete(
            UUID accountId,
            UUID battleId,
            UUID requestId,
            BattleCompletionCommand command) {
        validateCompletion(command);
        return repository.complete(
                accountId, battleId, requestId, hash(command), command,
                evaluator, catalog.configuration(), clock.now());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BattleHistoryPage history(UUID accountId, UUID before, int limit) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("History limit must be between 1 and 50");
        }
        return repository.history(accountId, before, limit);
    }

    private void validateStart(BattleStartCommand command) {
        if (command.stageCode() == null || command.stageCode().isBlank()
                || command.stageCode().length() > 80
                || command.clientBuild() == null || command.clientBuild().isBlank()
                || command.clientBuild().length() > 40) {
            throw new IllegalArgumentException("Invalid battle start command");
        }
    }

    private void validateCompletion(BattleCompletionCommand command) {
        if (command.outcome() == null || command.totalDamage() == null
                || command.clientEndedAt() == null
                || command.totalDamage().precision() > 24
                || command.totalDamage().scale() > 4) {
            throw new IllegalArgumentException("Invalid battle completion command");
        }
    }

    private String hash(Object command) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(command);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot hash battle command", exception);
        }
    }
}
