package com.nayon.api.legal;

import java.util.Optional;

public interface LegalDocumentRepository {
    Optional<LegalDocument> findActive(LegalDocumentType type, String locale);
}
