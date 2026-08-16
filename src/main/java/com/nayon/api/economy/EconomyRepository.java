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

    EconomySnapshot creditCurrency(
            UUID accountId,
            UUID requestId,
            String currencyCode,
            long amount,
            String reasonCode,
            String referenceType,
            UUID referenceId);

    default EconomySnapshot creditItem(
            UUID accountId,
            UUID requestId,
            String itemCode,
            long amount,
            String reasonCode,
            String referenceType,
            UUID referenceId) {
        throw new UnsupportedOperationException("Item credit is not implemented");
    }
}
