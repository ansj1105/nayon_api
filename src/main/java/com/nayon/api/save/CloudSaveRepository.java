package com.nayon.api.save;

import java.util.Optional;
import java.util.UUID;

public interface CloudSaveRepository {

    Optional<CloudSave> findByAccountId(UUID accountId);

    CloudSave create(UUID accountId, SaveContent content);

    Optional<CloudSave> updateIfRevision(
            UUID accountId, long expectedRevision, SaveContent content);

    Optional<SaveImportRecord> findImport(UUID requestId);

    CloudSave importInitial(UUID accountId, UUID requestId, SaveContent content);
}
