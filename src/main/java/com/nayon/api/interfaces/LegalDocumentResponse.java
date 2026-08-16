package com.nayon.api.interfaces;

import com.nayon.api.legal.LegalDocument;

import java.time.Instant;

public record LegalDocumentResponse(
        String type,
        String locale,
        String version,
        String title,
        String content,
        Instant effectiveAt,
        Instant publishedAt) {

    public static LegalDocumentResponse from(LegalDocument document) {
        return new LegalDocumentResponse(
                document.type().slug(),
                document.locale(),
                document.version(),
                document.title(),
                document.content(),
                document.effectiveAt(),
                document.publishedAt());
    }
}
