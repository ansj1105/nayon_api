package com.nayon.api.economy;

import java.util.Comparator;

public record EconomyBootstrapEquipment(
        String equipmentCode,
        String grade,
        int quantity) {

    static final Comparator<EconomyBootstrapEquipment> ORDER = Comparator
            .comparing(EconomyBootstrapEquipment::equipmentCode)
            .thenComparing(EconomyBootstrapEquipment::grade)
            .thenComparingInt(EconomyBootstrapEquipment::quantity);
}
