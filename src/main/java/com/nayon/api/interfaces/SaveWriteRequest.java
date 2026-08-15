package com.nayon.api.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.nayon.api.save.SaveContent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SaveWriteRequest(
        @Min(0) long expectedRevision,
        @Min(1) int schemaVersion,
        @NotNull JsonNode payload,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String checksum,
        @NotBlank @Size(max = 40) String clientBuild) {

    public SaveContent content() {
        return new SaveContent(schemaVersion, payload, checksum, clientBuild);
    }
}
