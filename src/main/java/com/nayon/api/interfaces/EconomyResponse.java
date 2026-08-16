package com.nayon.api.interfaces;

import com.nayon.api.economy.EconomySnapshot;

import java.util.List;
import java.util.Map;

public record EconomyResponse(
        boolean bootstrapped,
        Map<String, Long> currencies,
        Map<String, Long> items,
        List<EconomyEquipmentResponse> equipment) {

    static EconomyResponse from(EconomySnapshot snapshot) {
        return new EconomyResponse(
                snapshot.bootstrapped(),
                snapshot.currencies(),
                snapshot.items(),
                snapshot.equipment().stream()
                        .map(EconomyEquipmentResponse::from)
                        .toList());
    }
}
