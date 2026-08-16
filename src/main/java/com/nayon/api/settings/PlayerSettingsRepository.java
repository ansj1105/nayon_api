package com.nayon.api.settings;

import java.util.Optional;
import java.util.UUID;

public interface PlayerSettingsRepository {
    Optional<PlayerSettings> findByAccountId(UUID accountId);

    PlayerSettings upsert(UUID accountId, PlayerSettingsPatch patch);
}
