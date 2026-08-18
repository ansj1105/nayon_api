package com.nayon.api.share;

import com.nayon.api.economy.EconomyBootstrapCommand;
import com.nayon.api.economy.EconomyBootstrapRecord;
import com.nayon.api.economy.EconomyBootstrapResult;
import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShareRewardServiceTest {

    private final MemoryShareRepository shareRepository = new MemoryShareRepository();
    private final MemoryEconomyRepository economyRepository = new MemoryEconomyRepository();
    private final ShareRewardService service =
            new ShareRewardService(shareRepository, economyRepository);

    @Test
    void initialStateAndShareOpenedAreAccountScopedAndIdempotent() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(service.get(first).state()).isEqualTo(ShareRewardState.initial(first));

        ShareRewardResult opened = service.markOpened(first, "com.kakao.talk");
        ShareRewardResult replay = service.markOpened(first, "com.other.app");

        assertThat(opened.state().shared()).isTrue();
        assertThat(replay.state()).isEqualTo(opened.state());
        assertThat(service.get(second).state().shared()).isFalse();
    }

    @Test
    void claimRequiresShareAndCreditsDiamondExactlyOnce() {
        UUID accountId = UUID.randomUUID();
        economyRepository.bootstrap(accountId, 100);

        assertThatThrownBy(() -> service.claim(accountId, UUID.randomUUID()))
                .isInstanceOf(ShareRequiredException.class);

        service.markOpened(accountId, null);
        ShareRewardResult first = service.claim(accountId, UUID.randomUUID());
        ShareRewardResult replay = service.claim(accountId, UUID.randomUUID());

        assertThat(first.state().rewardClaimed()).isTrue();
        assertThat(first.economy().currencies()).containsEntry("DIAMOND", 150L);
        assertThat(replay.economy().currencies()).containsEntry("DIAMOND", 150L);
        assertThat(economyRepository.creditWrites).isEqualTo(1);
    }

    @Test
    void claimRequiresBootstrappedEconomy() {
        UUID accountId = UUID.randomUUID();
        service.markOpened(accountId, null);

        assertThatThrownBy(() -> service.claim(accountId, UUID.randomUUID()))
                .isInstanceOf(EconomyNotBootstrappedForShareException.class);
    }

    static final class MemoryShareRepository implements ShareRewardRepository {
        private final Map<UUID, ShareRewardState> states = new HashMap<>();

        @Override
        public Optional<ShareRewardState> findByAccountId(UUID accountId) {
            return Optional.ofNullable(states.get(accountId));
        }

        @Override
        public ShareRewardState markOpened(UUID accountId, String target) {
            return states.compute(accountId, (ignored, current) -> current == null
                    ? ShareRewardState.opened(
                            accountId, UUID.randomUUID(), target, Instant.now())
                    : current.open(target, Instant.now()));
        }

        @Override
        public ShareRewardState lockOrCreate(UUID accountId) {
            return states.computeIfAbsent(accountId, ShareRewardState::initialPersisted);
        }

        @Override
        public ShareRewardState markClaimed(UUID accountId) {
            ShareRewardState claimed = states.get(accountId).claim(Instant.now());
            states.put(accountId, claimed);
            return claimed;
        }
    }

    static final class MemoryEconomyRepository implements EconomyRepository {
        private final Map<UUID, EconomySnapshot> snapshots = new HashMap<>();
        private int creditWrites;

        void bootstrap(UUID accountId, long diamonds) {
            snapshots.put(accountId, new EconomySnapshot(
                    accountId, Map.of("DIAMOND", diamonds), Map.of(), List.of(), true));
        }

        @Override
        public EconomySnapshot findSnapshot(UUID accountId) {
            return snapshots.getOrDefault(accountId, EconomySnapshot.empty(accountId));
        }

        @Override
        public EconomySnapshot creditCurrency(
                UUID accountId, UUID requestId, String currencyCode, long amount,
                String reasonCode, String referenceType, UUID referenceId) {
            creditWrites++;
            EconomySnapshot current = findSnapshot(accountId);
            Map<String, Long> currencies = new HashMap<>(current.currencies());
            currencies.merge(currencyCode, amount, Long::sum);
            EconomySnapshot updated = new EconomySnapshot(
                    accountId, currencies, current.items(), current.equipment(), true);
            snapshots.put(accountId, updated);
            return updated;
        }

        @Override
        public Optional<EconomyBootstrapRecord> findBootstrapByAccountId(UUID accountId) {
            return Optional.empty();
        }

        @Override
        public Optional<EconomyBootstrapRecord> findBootstrapByRequestId(UUID requestId) {
            return Optional.empty();
        }

        @Override
        public EconomyBootstrapResult createBootstrap(
                UUID accountId, UUID requestId, String requestHash,
                EconomyBootstrapCommand command) {
            throw new UnsupportedOperationException();
        }
    }
}
