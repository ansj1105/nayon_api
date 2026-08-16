package com.nayon.api.interfaces;

import com.nayon.api.economy.EconomyBootstrapEquipment;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EconomyBootstrapEquipmentRequest(
        @NotBlank @Size(max = 80) String equipmentCode,
        @Pattern(regexp = "COMMON|UNCOMMON|RARE|EPIC|UNIQUE") String grade,
        @Min(1) @Max(10_000) int quantity) {

    EconomyBootstrapEquipment toDomain() {
        return new EconomyBootstrapEquipment(equipmentCode, grade, quantity);
    }
}
