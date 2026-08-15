package com.nayon.api.save;

import java.util.UUID;

public record SaveImportRecord(
        UUID accountId,
        UUID requestId,
        String checksum,
        CloudSave result) {
}
