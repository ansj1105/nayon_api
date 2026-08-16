package com.nayon.api.interfaces;

import com.nayon.api.economy.EconomyBootstrapCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record EconomyBootstrapRequest(
        @NotNull @Size(max = 2)
        Map<@Pattern(regexp = "DIAMOND|GOLD") String,
                @NotNull @PositiveOrZero @Max(1_000_000_000L) Long> currencies,
        @NotNull @Size(max = 3)
        Map<@Pattern(regexp = "SILVER_KEY|GOLD_KEY|CHROMA_FRAGMENT") String,
                @NotNull @PositiveOrZero @Max(1_000_000_000L) Long> items,
        @NotNull @Size(max = 10_000)
        List<@NotNull @Valid EconomyBootstrapEquipmentRequest> equipment) {

    EconomyBootstrapCommand toCommand() {
        return new EconomyBootstrapCommand(
                currencies,
                items,
                equipment.stream()
                        .map(EconomyBootstrapEquipmentRequest::toDomain)
                        .toList());
    }
}
