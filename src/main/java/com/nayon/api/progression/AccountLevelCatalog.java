package com.nayon.api.progression;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class AccountLevelCatalog {

    private final int maxLevel;
    private final List<Long> requiredExp;

    public AccountLevelCatalog(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource(
                "progression/account-level-catalog-v1.json").getInputStream()) {
            Catalog catalog = objectMapper.readValue(input, Catalog.class);
            if (catalog.maxLevel() < 1
                    || catalog.requiredExp().size() != catalog.maxLevel() - 1
                    || catalog.requiredExp().stream().anyMatch(value -> value <= 0)) {
                throw new IllegalStateException("Account level catalog is invalid");
            }
            this.maxLevel = catalog.maxLevel();
            this.requiredExp = List.copyOf(catalog.requiredExp());
        } catch (IOException exception) {
            throw new IllegalStateException("Account level catalog cannot be loaded", exception);
        }
    }

    public int level(long totalAccountExp) {
        long remaining = Math.max(0, totalAccountExp);
        int level = 1;
        for (long required : requiredExp) {
            if (remaining < required) {
                break;
            }
            remaining -= required;
            level++;
        }
        return Math.min(level, maxLevel);
    }

    public int maxLevel() {
        return maxLevel;
    }

    private record Catalog(int maxLevel, List<Long> requiredExp) {
    }
}
