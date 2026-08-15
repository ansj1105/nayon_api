package com.nayon.api.save;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class CloudSaveService {

    private final CloudSaveRepository repository;

    public CloudSaveService(CloudSaveRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<CloudSave> get(UUID accountId) {
        return repository.findByAccountId(accountId);
    }

    @Transactional
    public CloudSave put(
            UUID accountId,
            long expectedRevision,
            SaveContent content) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision cannot be negative");
        }
        if (expectedRevision == 0) {
            if (repository.findByAccountId(accountId).isPresent()) {
                throw new SaveRevisionConflictException();
            }
            return repository.create(accountId, content);
        }
        return repository.updateIfRevision(accountId, expectedRevision, content)
                .orElseThrow(SaveRevisionConflictException::new);
    }

    @Transactional
    public CloudSave importInitial(
            UUID accountId,
            UUID requestId,
            SaveContent content) {
        Optional<SaveImportRecord> replay = repository.findImport(requestId);
        if (replay.isPresent()) {
            SaveImportRecord previous = replay.get();
            if (previous.accountId().equals(accountId)
                    && previous.checksum().equals(content.checksum())) {
                return previous.result();
            }
            throw new IdempotencyConflictException();
        }
        if (repository.findByAccountId(accountId).isPresent()) {
            throw new SaveRevisionConflictException();
        }
        return repository.importInitial(accountId, requestId, content);
    }
}
