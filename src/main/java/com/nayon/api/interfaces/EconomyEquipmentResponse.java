package com.nayon.api.interfaces;

import com.nayon.api.economy.PlayerEquipment;

import java.util.UUID;

public record EconomyEquipmentResponse(
        UUID id,
        String equipmentCode,
        String grade,
        int level,
        boolean locked) {

    static EconomyEquipmentResponse from(PlayerEquipment equipment) {
        return new EconomyEquipmentResponse(
                equipment.id(),
                equipment.equipmentCode(),
                equipment.grade(),
                equipment.level(),
                equipment.locked());
    }
}
