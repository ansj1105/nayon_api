package com.nayon.api.economy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EconomyServiceTest {

    private static final UUID ACCOUNT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private final InMemoryEconomyRepository repository = new InMemoryEconomyRepository();
    private final EconomyService service = new EconomyService(repository, new ObjectMapper());

    @Test
    void newAccountHasEmptyUnbootstrappedEconomy() {
        EconomySnapshot snapshot = service.get(ACCOUNT_A);

        assertThat(snapshot.currencies()).isEmpty();
        assertThat(snapshot.items()).isEmpty();
        assertThat(snapshot.equipment()).isEmpty();
        assertThat(snapshot.bootstrapped()).isFalse();
    }

    @Test
    void bootstrapStoresSupportedAssetsAndEquipmentOnce() {
        EconomyBootstrapResult result = service.bootstrap(
                ACCOUNT_A, REQUEST_ID, command(100, 2));

        assertThat(result.replay()).isFalse();
        assertThat(result.snapshot().currencies()).containsEntry("DIAMOND", 100L);
        assertThat(result.snapshot().items()).containsEntry("SILVER_KEY", 2L);
        assertThat(result.snapshot().equipment()).hasSize(1);
        assertThat(result.snapshot().bootstrapped()).isTrue();
    }

    @Test
    void identicalBootstrapRetryReturnsOriginalResult() {
        EconomyBootstrapResult first = service.bootstrap(
                ACCOUNT_A, REQUEST_ID, command(100, 2));

        EconomyBootstrapResult replay = service.bootstrap(
                ACCOUNT_A, REQUEST_ID, command(100, 2));

        assertThat(replay.replay()).isTrue();
        assertThat(replay.snapshot()).isEqualTo(first.snapshot());
        assertThat(repository.bootstrapWrites).isEqualTo(1);
    }

    @Test
    void changedBootstrapForSameAccountIsRejected() {
        service.bootstrap(ACCOUNT_A, REQUEST_ID, command(100, 2));

        assertThatThrownBy(() -> service.bootstrap(
                ACCOUNT_A, UUID.randomUUID(), command(101, 2)))
                .isInstanceOf(EconomyBootstrapConflictException.class);
    }

    @Test
    void requestKeyCannotBeReusedByAnotherAccount() {
        service.bootstrap(ACCOUNT_A, REQUEST_ID, command(100, 2));

        assertThatThrownBy(() -> service.bootstrap(
                ACCOUNT_B, REQUEST_ID, command(100, 2)))
                .isInstanceOf(EconomyBootstrapConflictException.class);
    }

    @Test
    void negativeBootstrapValueIsRejectedBeforePersistence() {
        EconomyBootstrapCommand invalid = new EconomyBootstrapCommand(
                Map.of("DIAMOND", -1L), Map.of(), List.of());

        assertThatThrownBy(() -> service.bootstrap(ACCOUNT_A, REQUEST_ID, invalid))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.bootstrapWrites).isZero();
    }

    @Test
    void accountCannotReadAnotherAccountsEconomy() {
        service.bootstrap(ACCOUNT_A, REQUEST_ID, command(100, 2));

        EconomySnapshot snapshot = service.get(ACCOUNT_B);

        assertThat(snapshot.currencies()).isEmpty();
        assertThat(snapshot.equipment()).isEmpty();
    }

    @Test
    void bootstrapCommandKeepsCanonicalAssetOrderForStableHashing() {
        Map<String, Long> reversed = new LinkedHashMap<>();
        reversed.put("GOLD", 2L);
        reversed.put("DIAMOND", 1L);

        EconomyBootstrapCommand command = new EconomyBootstrapCommand(
                reversed, Map.of(), List.of());

        assertThat(command.currencies().keySet())
                .containsExactly("DIAMOND", "GOLD");
    }

    @Test
    void economyReadUsesOneRepeatableDatabaseSnapshot() throws Exception {
        Transactional transaction = EconomyService.class
                .getMethod("get", UUID.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    void reorderedEquivalentBootstrapReplaysOriginalResult() {
        Map<String, Long> firstCurrencies = new LinkedHashMap<>();
        firstCurrencies.put("GOLD", 2L);
        firstCurrencies.put("DIAMOND", 1L);
        EconomyBootstrapCommand first = new EconomyBootstrapCommand(
                firstCurrencies,
                Map.of("GOLD_KEY", 1L, "SILVER_KEY", 2L),
                List.of(
                        new EconomyBootstrapEquipment("Weapon_02", "RARE", 1),
                        new EconomyBootstrapEquipment("Weapon_01", "COMMON", 1)));
        EconomyBootstrapCommand reordered = new EconomyBootstrapCommand(
                Map.of("DIAMOND", 1L, "GOLD", 2L),
                Map.of("SILVER_KEY", 2L, "GOLD_KEY", 1L),
                List.of(
                        new EconomyBootstrapEquipment("Weapon_01", "COMMON", 1),
                        new EconomyBootstrapEquipment("Weapon_02", "RARE", 1)));

        service.bootstrap(ACCOUNT_A, REQUEST_ID, first);
        EconomyBootstrapResult replay = service.bootstrap(
                ACCOUNT_A, REQUEST_ID, reordered);

        assertThat(replay.replay()).isTrue();
    }

    private EconomyBootstrapCommand command(long diamonds, long silverKeys) {
        return new EconomyBootstrapCommand(
                Map.of("DIAMOND", diamonds),
                Map.of("SILVER_KEY", silverKeys),
                List.of(new EconomyBootstrapEquipment("Weapon_01", "COMMON", 1)));
    }

    private static final class InMemoryEconomyRepository implements EconomyRepository {
        private final Map<UUID, EconomySnapshot> snapshots = new HashMap<>();
        private final Map<UUID, EconomyBootstrapRecord> requests = new HashMap<>();
        private int bootstrapWrites;

        @Override
        public EconomySnapshot findSnapshot(UUID accountId) {
            return snapshots.getOrDefault(accountId, EconomySnapshot.empty(accountId));
        }

        @Override
        public Optional<EconomyBootstrapRecord> findBootstrapByAccountId(UUID accountId) {
            return requests.values().stream()
                    .filter(record -> record.accountId().equals(accountId))
                    .findFirst();
        }

        @Override
        public Optional<EconomyBootstrapRecord> findBootstrapByRequestId(UUID requestId) {
            return Optional.ofNullable(requests.get(requestId));
        }

        @Override
        public EconomyBootstrapResult createBootstrap(
                UUID accountId,
                UUID requestId,
                String requestHash,
                EconomyBootstrapCommand command) {
            bootstrapWrites++;
            List<PlayerEquipment> equipment = new ArrayList<>();
            for (EconomyBootstrapEquipment item : command.equipment()) {
                for (int index = 0; index < item.quantity(); index++) {
                    equipment.add(new PlayerEquipment(
                            UUID.randomUUID(), item.equipmentCode(), item.grade(), 1, false));
                }
            }
            EconomySnapshot snapshot = new EconomySnapshot(
                    accountId, command.currencies(), command.items(), equipment, true);
            snapshots.put(accountId, snapshot);
            requests.put(requestId, new EconomyBootstrapRecord(
                    accountId, requestId, requestHash, snapshot,
                    Instant.parse("2026-08-16T00:00:00Z")));
            return new EconomyBootstrapResult(snapshot, false);
        }

        @Override
        public EconomySnapshot creditCurrency(
                UUID accountId, UUID requestId, String currencyCode, long amount,
                String reasonCode, String referenceType, UUID referenceId) {
            throw new UnsupportedOperationException();
        }
    }
}
