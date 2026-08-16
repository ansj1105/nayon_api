package com.nayon.api.legal;

import org.springframework.stereotype.Service;

@Service
public class LegalDocumentService {
    private final LegalDocumentRepository repository;

    public LegalDocumentService(LegalDocumentRepository repository) {
        this.repository = repository;
    }

    public LegalDocument get(String typeSlug, String locale) {
        LegalDocumentType type = LegalDocumentType.fromSlug(typeSlug);
        String normalizedLocale = normalizeLocale(locale);
        return repository.findActive(type, normalizedLocale)
                .orElseThrow(LegalDocumentNotFoundException::new);
    }

    private static String normalizeLocale(String locale) {
        String value = locale == null ? "" : locale.trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[a-z]{2}(?:-[a-z0-9]{2,8})?")) {
            throw new IllegalArgumentException("Unsupported locale");
        }
        return value;
    }
}
