package com.nayon.api.interfaces;

import com.nayon.api.gacha.GachaBanner;
import com.nayon.api.gacha.GachaDrawCommand;
import com.nayon.api.gacha.GachaPayment;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record GachaDrawRequest(
        @NotNull GachaBanner banner,
        @NotNull GachaPayment payment,
        @Min(1) @Max(10) int count) {

    GachaDrawCommand toCommand() {
        return new GachaDrawCommand(banner, payment, count);
    }
}
