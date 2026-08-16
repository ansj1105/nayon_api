package com.nayon.api.legal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalDocumentServiceTest {
    @Test
    void resolvesOnlyTheRequestedPublishedTypeAndLocale() {
        LegalDocumentRepository repository = mock(LegalDocumentRepository.class);
        LegalDocument document = new LegalDocument(
                UUID.randomUUID(), LegalDocumentType.PRIVACY_POLICY, "ko", "2026-08-17",
                "개인정보 처리방침", "승인된 본문", Instant.EPOCH, Instant.EPOCH);
        when(repository.findActive(LegalDocumentType.PRIVACY_POLICY, "ko"))
                .thenReturn(Optional.of(document));

        LegalDocument result = new LegalDocumentService(repository)
                .get("privacy-policy", "KO");

        assertThat(result).isEqualTo(document);
        verify(repository).findActive(LegalDocumentType.PRIVACY_POLICY, "ko");
    }

    @Test
    void rejectsUnknownTypesAndDoesNotGuessMissingDocuments() {
        LegalDocumentRepository repository = mock(LegalDocumentRepository.class);
        when(repository.findActive(LegalDocumentType.TERMS_OF_SERVICE, "ko"))
                .thenReturn(Optional.empty());
        LegalDocumentService service = new LegalDocumentService(repository);

        assertThatThrownBy(() -> service.get("other", "ko"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.get("terms-of-service", "ko"))
                .isInstanceOf(LegalDocumentNotFoundException.class);
    }
}
