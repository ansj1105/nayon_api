package com.nayon.api.interfaces;

import jakarta.validation.constraints.Size;

public record ShareOpenedRequest(
        @Size(min = 1, max = 255) String target) {
}
