package com.nayon.api.interfaces;

import com.nayon.api.settings.PlayerSettings;

public record PlayerSettingsResponse(
        boolean effectSoundEnabled,
        boolean backgroundMusicEnabled,
        boolean reducedEffectsEnabled,
        boolean reducedCriticalEffectsEnabled,
        boolean damageNumbersEnabled,
        boolean joystickVisible,
        String languageCode,
        long revision) {

    static PlayerSettingsResponse from(PlayerSettings settings) {
        return new PlayerSettingsResponse(
                settings.effectSoundEnabled(),
                settings.backgroundMusicEnabled(),
                settings.reducedEffectsEnabled(),
                settings.reducedCriticalEffectsEnabled(),
                settings.damageNumbersEnabled(),
                settings.joystickVisible(),
                settings.languageCode(),
                settings.revision());
    }
}
