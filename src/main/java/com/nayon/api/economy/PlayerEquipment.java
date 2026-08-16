package com.nayon.api.economy;

import java.util.UUID;

public record PlayerEquipment(
        UUID id,
        String equipmentCode,
        String grade,
        int level,
        boolean locked) {
}
