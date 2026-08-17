package com.nayon.api.subscription.rtdn;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GooglePlayRtdnPushRequest(
        @NotNull @Valid Message message,
        @Size(max = 500) String subscription) {

    public record Message(
            @NotBlank @Size(max = 200) String messageId,
            @NotBlank @Size(max = 16384) String data) {
    }
}
