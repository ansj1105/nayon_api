package com.nayon.api.save;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudSaveServiceTest {

    private static final UUID ACCOUNT_A =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_B =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REQUEST_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final String CHECKSUM_A = "a".repeat(64);
    private static final String CHECKSUM_B = "b".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemoryCloudSaveRepository repository =
            new InMemoryCloudSaveRepository();
    private final CloudSaveService service = new CloudSaveService(repository);

    @Test
    void firstSaveStartsAtRevisionOne() {
        CloudSave saved = service.put(ACCOUNT_A, 0, content(CHECKSUM_A));

        assertThat(saved.revision()).isEqualTo(1);
    }

    @Test
    void matchingExpectedRevisionIncrementsRevision() {
        service.put(ACCOUNT_A, 0, content(CHECKSUM_A));

        CloudSave saved = service.put(ACCOUNT_A, 1, content(CHECKSUM_B));

        assertThat(saved.revision()).isEqualTo(2);
        assertThat(saved.checksum()).isEqualTo(CHECKSUM_B);
    }

    @Test
    void staleExpectedRevisionIsRejected() {
        service.put(ACCOUNT_A, 0, content(CHECKSUM_A));

        assertThatThrownBy(() -> service.put(ACCOUNT_A, 0, content(CHECKSUM_B)))
                .isInstanceOf(SaveRevisionConflictException.class);
    }

    @Test
    void accountCannotReadAnotherAccountsSave() {
        service.put(ACCOUNT_A, 0, content(CHECKSUM_A));

        assertThat(service.get(ACCOUNT_B)).isEmpty();
    }

    @Test
    void identicalImportRetryReturnsOriginalOutcome() {
        CloudSave first = service.importInitial(
                ACCOUNT_A, REQUEST_ID, content(CHECKSUM_A));
        service.put(ACCOUNT_A, 1, content(CHECKSUM_B));

        CloudSave retried = service.importInitial(
                ACCOUNT_A, REQUEST_ID, content(CHECKSUM_A));

        assertThat(retried).isEqualTo(first);
        assertThat(retried.revision()).isEqualTo(1);
    }

    @Test
    void sameImportKeyWithDifferentChecksumIsRejected() {
        service.importInitial(ACCOUNT_A, REQUEST_ID, content(CHECKSUM_A));

        assertThatThrownBy(() -> service.importInitial(
                ACCOUNT_A, REQUEST_ID, content(CHECKSUM_B)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    private SaveContent content(String checksum) {
        JsonNode payload = objectMapper.createObjectNode().put("nickname", "Nyaon");
        return new SaveContent(1, payload, checksum, "0.1.0-test");
    }

    private static final class InMemoryCloudSaveRepository
            implements CloudSaveRepository {
        private final Map<UUID, CloudSave> saves = new HashMap<>();
        private final Map<UUID, SaveImportRecord> imports = new HashMap<>();

        @Override
        public Optional<CloudSave> findByAccountId(UUID accountId) {
            return Optional.ofNullable(saves.get(accountId));
        }

        @Override
        public CloudSave create(UUID accountId, SaveContent content) {
            CloudSave saved = save(accountId, 1, content);
            if (saves.putIfAbsent(accountId, saved) != null) {
                throw new SaveRevisionConflictException();
            }
            return saved;
        }

        @Override
        public Optional<CloudSave> updateIfRevision(
                UUID accountId, long expectedRevision, SaveContent content) {
            CloudSave current = saves.get(accountId);
            if (current == null || current.revision() != expectedRevision) {
                return Optional.empty();
            }
            CloudSave saved = save(accountId, expectedRevision + 1, content);
            saves.put(accountId, saved);
            return Optional.of(saved);
        }

        @Override
        public Optional<SaveImportRecord> findImport(UUID requestId) {
            return Optional.ofNullable(imports.get(requestId));
        }

        @Override
        public CloudSave importInitial(
                UUID accountId, UUID requestId, SaveContent content) {
            CloudSave saved = create(accountId, content);
            imports.put(requestId,
                    new SaveImportRecord(accountId, requestId, content.checksum(), saved));
            return saved;
        }

        private CloudSave save(UUID accountId, long revision, SaveContent content) {
            return new CloudSave(
                    accountId,
                    content.schemaVersion(),
                    revision,
                    content.payload(),
                    content.checksum(),
                    content.clientBuild(),
                    Instant.parse("2026-08-15T00:00:00Z"));
        }
    }
}
