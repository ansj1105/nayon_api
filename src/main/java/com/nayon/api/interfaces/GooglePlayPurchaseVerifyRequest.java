package com.nayon.api.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GooglePlayPurchaseVerifyRequest(
        @NotBlank @Size(max = 200) String productId,
        @NotBlank @Size(max = 4096) String purchaseToken) {
}
