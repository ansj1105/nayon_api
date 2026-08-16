package com.nayon.api.legal;

import java.time.Instant;
import java.util.UUID;

public record LegalDocument(
        UUID id,
        LegalDocumentType type,
        String locale,
        String version,
        String title,
        String content,
        Instant effectiveAt,
        Instant publishedAt) {
}
