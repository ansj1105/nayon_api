package com.nayon.api.economy;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;

public record EconomyBootstrapCommand(
        Map<String, Long> currencies,
        Map<String, Long> items,
        List<EconomyBootstrapEquipment> equipment) {

    public EconomyBootstrapCommand {
        currencies = Collections.unmodifiableMap(new TreeMap<>(currencies));
        items = Collections.unmodifiableMap(new TreeMap<>(items));
        equipment = equipment.stream()
                .sorted(EconomyBootstrapEquipment.ORDER)
                .toList();
    }
}
