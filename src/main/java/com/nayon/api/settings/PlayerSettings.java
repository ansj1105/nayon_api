package com.nayon.api.settings;

import java.util.UUID;

public record PlayerSettings(
        UUID accountId,
        boolean effectSoundEnabled,
        boolean backgroundMusicEnabled,
        boolean reducedEffectsEnabled,
        boolean reducedCriticalEffectsEnabled,
        boolean damageNumbersEnabled,
        boolean joystickVisible,
        String languageCode,
        long revision) {

    public static PlayerSettings defaults(UUID accountId) {
        return new PlayerSettings(
                accountId, true, true, false, false, true, true, "en", 0);
    }
}
