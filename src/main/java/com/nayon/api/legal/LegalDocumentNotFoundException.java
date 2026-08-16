package com.nayon.api.legal;

public class LegalDocumentNotFoundException extends RuntimeException {
    public LegalDocumentNotFoundException() {
        super("The requested legal document is not published.");
    }
}
