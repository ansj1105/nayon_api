package com.nayon.api.economy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EconomySnapshot(
        UUID accountId,
        Map<String, Long> currencies,
        Map<String, Long> items,
        List<PlayerEquipment> equipment,
        boolean bootstrapped) {

    public EconomySnapshot {
        currencies = Map.copyOf(currencies);
        items = Map.copyOf(items);
        equipment = List.copyOf(equipment);
    }

    public static EconomySnapshot empty(UUID accountId) {
        return new EconomySnapshot(accountId, Map.of(), Map.of(), List.of(), false);
    }
}
