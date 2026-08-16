package com.nayon.api.gacha;

import java.util.UUID;

public record GachaAward(
        UUID equipmentId,
        String equipmentCode,
        String grade,
        boolean chroma) {
}
