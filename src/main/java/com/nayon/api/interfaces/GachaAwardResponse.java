package com.nayon.api.interfaces;

import com.nayon.api.gacha.GachaAward;

import java.util.UUID;

public record GachaAwardResponse(
        UUID equipmentId,
        String equipmentCode,
        String grade,
        boolean chroma) {

    static GachaAwardResponse from(GachaAward award) {
        return new GachaAwardResponse(
                award.equipmentId(), award.equipmentCode(),
                award.grade(), award.chroma());
    }
}
