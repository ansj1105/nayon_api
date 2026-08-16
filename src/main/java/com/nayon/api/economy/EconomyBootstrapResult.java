package com.nayon.api.economy;

public record EconomyBootstrapResult(
        EconomySnapshot snapshot,
        boolean replay) {
}
