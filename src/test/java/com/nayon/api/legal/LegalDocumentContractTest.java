package com.nayon.api.legal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LegalDocumentContractTest {
    @Test
    void publicRouteSchemaAndSecurityStayAligned() throws Exception {
        String openApi = Files.readString(
                Path.of("src/main/resources/openapi/nayon-api-v1.yaml"));
        String security = Files.readString(
                Path.of("src/main/java/com/nayon/api/config/SecurityConfig.java"));
        String controller = Files.readString(
                Path.of("src/main/java/com/nayon/api/interfaces/LegalDocumentController.java"));

        assertThat(openApi)
                .contains("/legal-documents/{type}:")
                .contains("security: []")
                .contains("privacy-policy", "terms-of-service")
                .contains("LegalDocumentResponse:");
        assertThat(security).contains("/api/v1/legal-documents/**");
        assertThat(controller).contains("/api/v1/legal-documents");
    }
}
