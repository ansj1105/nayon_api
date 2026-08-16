package com.nayon.api.economy;

import java.util.Optional;
import java.util.UUID;

public interface EconomyRepository {

    EconomySnapshot findSnapshot(UUID accountId);

    Optional<EconomyBootstrapRecord> findBootstrapByAccountId(UUID accountId);

    Optional<EconomyBootstrapRecord> findBootstrapByRequestId(UUID requestId);

    EconomyBootstrapResult createBootstrap(
            UUID accountId,
            UUID requestId,
            String requestHash,
            EconomyBootstrapCommand command);
}
