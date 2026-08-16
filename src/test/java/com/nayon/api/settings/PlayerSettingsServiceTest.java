package com.nayon.api.settings;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerSettingsServiceTest {

    private final MemoryRepository repository = new MemoryRepository();
    private final PlayerSettingsService service = new PlayerSettingsService(repository);

    @Test
    void settingsStayIsolatedByAccountAndRevisionIncrements() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        PlayerSettings updated = service.patch(first, new PlayerSettingsPatch(
                false, null, null, null, null, null, "ko"));

        assertThat(updated.revision()).isEqualTo(1);
        assertThat(service.get(first).effectSoundEnabled()).isFalse();
        assertThat(service.get(second)).isEqualTo(PlayerSettings.defaults(second));
    }

    @Test
    void rejectsEmptyPatchAndUnknownLanguage() {
        UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() -> service.patch(accountId, PlayerSettingsPatch.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.patch(accountId, new PlayerSettingsPatch(
                null, null, null, null, null, null, "xx")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static final class MemoryRepository implements PlayerSettingsRepository {
        private final Map<UUID, PlayerSettings> values = new HashMap<>();

        @Override
        public Optional<PlayerSettings> findByAccountId(UUID accountId) {
            return Optional.ofNullable(values.get(accountId));
        }

        @Override
        public PlayerSettings upsert(UUID accountId, PlayerSettingsPatch patch) {
            PlayerSettings next = patch.applyTo(values.getOrDefault(
                    accountId, PlayerSettings.defaults(accountId)));
            values.put(accountId, next);
            return next;
        }
    }
}
