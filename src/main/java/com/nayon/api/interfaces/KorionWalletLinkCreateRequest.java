package com.nayon.api.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record KorionWalletLinkCreateRequest(
        @NotBlank
        @Pattern(regexp = "^T[1-9A-HJ-NP-Za-km-z]{33}$")
        String address) {
}
