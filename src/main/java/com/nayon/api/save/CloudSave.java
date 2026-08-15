package com.nayon.api.save;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record CloudSave(
        UUID accountId,
        int schemaVersion,
        long revision,
        JsonNode payload,
        String checksum,
        String clientBuild,
        Instant updatedAt) {
}
