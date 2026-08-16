package com.nayon.api.interfaces;

import com.nayon.api.legal.LegalDocumentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/legal-documents")
public class LegalDocumentController {
    private final LegalDocumentService service;

    public LegalDocumentController(LegalDocumentService service) {
        this.service = service;
    }

    @GetMapping("/{type}")
    public LegalDocumentResponse get(
            @PathVariable String type,
            @RequestParam(defaultValue = "ko") String locale) {
        return LegalDocumentResponse.from(service.get(type, locale));
    }
}
