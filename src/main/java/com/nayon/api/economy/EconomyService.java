package com.nayon.api.economy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class EconomyService {

    private static final Set<String> CURRENCIES = Set.of("DIAMOND", "GOLD");
    private static final Set<String> ITEMS = Set.of(
            "SILVER_KEY", "GOLD_KEY", "CHROMA_FRAGMENT",
            "RANDOM_SCROLL", "LEVEL_UP_COUPON");
    private static final Set<String> GRADES = Set.of(
            "COMMON", "UNCOMMON", "RARE", "EPIC", "UNIQUE");
    private static final long MAX_ASSET_VALUE = 1_000_000_000L;
    private static final int MAX_EQUIPMENT_QUANTITY = 10_000;

    private final EconomyRepository repository;
    private final ObjectMapper objectMapper;

    public EconomyService(EconomyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EconomySnapshot get(UUID accountId) {
        return repository.findSnapshot(accountId);
    }

    @Transactional
    public EconomyBootstrapResult bootstrap(
            UUID accountId,
            UUID requestId,
            EconomyBootstrapCommand command) {
        validate(command);
        String requestHash = hash(command);

        Optional<EconomyBootstrapRecord> byRequest =
                repository.findBootstrapByRequestId(requestId);
        if (byRequest.isPresent()) {
            EconomyBootstrapRecord previous = byRequest.get();
            if (previous.accountId().equals(accountId)
                    && previous.requestHash().equals(requestHash)) {
                return new EconomyBootstrapResult(previous.snapshot(), true);
            }
            throw new EconomyBootstrapConflictException();
        }

        Optional<EconomyBootstrapRecord> byAccount =
                repository.findBootstrapByAccountId(accountId);
        if (byAccount.isPresent()) {
            EconomyBootstrapRecord previous = byAccount.get();
            if (previous.requestId().equals(requestId)
                    && previous.requestHash().equals(requestHash)) {
                return new EconomyBootstrapResult(previous.snapshot(), true);
            }
            throw new EconomyBootstrapConflictException();
        }

        return repository.createBootstrap(
                accountId, requestId, requestHash, command);
    }

    private void validate(EconomyBootstrapCommand command) {
        validateAssets(command.currencies(), CURRENCIES, "currency");
        validateAssets(command.items(), ITEMS, "item");
        int totalEquipment = 0;
        for (EconomyBootstrapEquipment equipment : command.equipment()) {
            if (equipment.equipmentCode() == null
                    || equipment.equipmentCode().isBlank()
                    || equipment.equipmentCode().length() > 80
                    || !GRADES.contains(equipment.grade())
                    || equipment.quantity() < 1) {
                throw new IllegalArgumentException("Invalid bootstrap equipment");
            }
            totalEquipment += equipment.quantity();
            if (totalEquipment > MAX_EQUIPMENT_QUANTITY) {
                throw new IllegalArgumentException("Too many bootstrap equipment instances");
            }
        }
    }

    private void validateAssets(
            Map<String, Long> assets,
            Set<String> supportedCodes,
            String type) {
        for (Map.Entry<String, Long> asset : assets.entrySet()) {
            if (!supportedCodes.contains(asset.getKey())
                    || asset.getValue() == null
                    || asset.getValue() < 0
                    || asset.getValue() > MAX_ASSET_VALUE) {
                throw new IllegalArgumentException("Invalid bootstrap " + type);
            }
        }
    }

    private String hash(EconomyBootstrapCommand command) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(command);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot hash economy bootstrap", exception);
        }
    }
}
