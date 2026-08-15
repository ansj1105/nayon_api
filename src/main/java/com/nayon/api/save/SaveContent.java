package com.nayon.api.save;

import com.fasterxml.jackson.databind.JsonNode;

public record SaveContent(
        int schemaVersion,
        JsonNode payload,
        String checksum,
        String clientBuild) {

    public SaveContent {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
        if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksum must be lowercase SHA-256 hex");
        }
        if (clientBuild == null || clientBuild.isBlank() || clientBuild.length() > 40) {
            throw new IllegalArgumentException("clientBuild length must be between 1 and 40");
        }
    }
}
