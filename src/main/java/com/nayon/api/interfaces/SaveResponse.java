package com.nayon.api.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.nayon.api.save.CloudSave;

import java.time.Instant;

public record SaveResponse(
        int schemaVersion,
        long revision,
        JsonNode payload,
        String checksum,
        String clientBuild,
        Instant updatedAt) {

    public static SaveResponse from(CloudSave save) {
        return new SaveResponse(
                save.schemaVersion(),
                save.revision(),
                save.payload(),
                save.checksum(),
                save.clientBuild(),
                save.updatedAt());
    }
}
