package com.nayon.api.gacha;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class GachaService {
    private static final Map<GachaDrawCommand, GachaSpec> SPECS = Map.of(
            new GachaDrawCommand(GachaBanner.COMMON, GachaPayment.SILVER_KEY, 1),
            new GachaSpec(GachaBanner.COMMON, GachaPayment.SILVER_KEY, 1, "ITEM", "SILVER_KEY", 1),
            new GachaDrawCommand(GachaBanner.ADVANCED, GachaPayment.GOLD_KEY, 1),
            new GachaSpec(GachaBanner.ADVANCED, GachaPayment.GOLD_KEY, 1, "ITEM", "GOLD_KEY", 1),
            new GachaDrawCommand(GachaBanner.CHROMA_SEASON_01, GachaPayment.CHROMA_FRAGMENT, 1),
            new GachaSpec(GachaBanner.CHROMA_SEASON_01, GachaPayment.CHROMA_FRAGMENT, 1, "ITEM", "CHROMA_FRAGMENT", 30),
            new GachaDrawCommand(GachaBanner.CHROMA_SEASON_01, GachaPayment.DIAMOND, 10),
            new GachaSpec(GachaBanner.CHROMA_SEASON_01, GachaPayment.DIAMOND, 10, "CURRENCY", "DIAMOND", 3200));

    private final GachaRepository repository;
    private final GachaEngine engine;
    private final ObjectMapper objectMapper;

    public GachaService(
            GachaRepository repository,
            GachaEngine engine,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.engine = engine;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GachaDrawResult draw(
            UUID accountId, UUID requestId, GachaDrawCommand command) {
        GachaSpec spec = SPECS.get(command);
        if (spec == null) {
            throw new IllegalArgumentException("Unsupported gacha banner, payment, or count");
        }
        return repository.draw(accountId, requestId, hash(command), spec, engine);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GachaHistoryPage history(UUID accountId, UUID before, int limit) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("History limit must be between 1 and 50");
        }
        return repository.history(accountId, before, limit);
    }

    private String hash(GachaDrawCommand command) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(command);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot hash gacha command", exception);
        }
    }
}
