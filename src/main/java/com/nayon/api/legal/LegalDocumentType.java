package com.nayon.api.legal;

public enum LegalDocumentType {
    PRIVACY_POLICY("privacy-policy"),
    TERMS_OF_SERVICE("terms-of-service");

    private final String slug;

    LegalDocumentType(String slug) {
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }

    public static LegalDocumentType fromSlug(String slug) {
        for (LegalDocumentType value : values()) {
            if (value.slug.equals(slug)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported legal document type");
    }
}
