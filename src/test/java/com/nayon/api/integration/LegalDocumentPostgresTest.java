package com.nayon.api.integration;

import com.nayon.api.legal.LegalDocument;
import com.nayon.api.legal.LegalDocumentNotFoundException;
import com.nayon.api.legal.LegalDocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "management.health.db.enabled=false")
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class LegalDocumentPostgresTest {
    @Autowired LegalDocumentService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table legal_documents");
    }

    @Test
    void servesOnlyTheEffectiveActiveVersionForTheExactLocale() {
        insert("privacy-policy", "PRIVACY_POLICY", "ko", "v1", true,
                Instant.now().minusSeconds(60));
        insert("future", "TERMS_OF_SERVICE", "ko", "v2", true,
                Instant.now().plusSeconds(3600));

        LegalDocument document = service.get("privacy-policy", "ko");

        assertThat(document.version()).isEqualTo("v1");
        assertThatThrownBy(() -> service.get("terms-of-service", "ko"))
                .isInstanceOf(LegalDocumentNotFoundException.class);
        assertThatThrownBy(() -> service.get("privacy-policy", "en"))
                .isInstanceOf(LegalDocumentNotFoundException.class);
    }

    private void insert(String idSeed, String type, String locale, String version,
                        boolean active, Instant effectiveAt) {
        jdbc.update("""
                insert into legal_documents(
                    id, document_type, locale, version, title, content,
                    effective_at, active)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.nameUUIDFromBytes(idSeed.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                type, locale, version, "test title", "test-only approved body",
                Timestamp.from(effectiveAt), active);
    }
}
