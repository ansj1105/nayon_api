package com.nayon.api.interfaces;

import com.nayon.api.settings.PlayerSettingsPatch;

public record PlayerSettingsPatchRequest(
        Boolean effectSoundEnabled,
        Boolean backgroundMusicEnabled,
        Boolean reducedEffectsEnabled,
        Boolean reducedCriticalEffectsEnabled,
        Boolean damageNumbersEnabled,
        Boolean joystickVisible,
        String languageCode) {

    PlayerSettingsPatch toPatch() {
        return new PlayerSettingsPatch(
                effectSoundEnabled,
                backgroundMusicEnabled,
                reducedEffectsEnabled,
                reducedCriticalEffectsEnabled,
                damageNumbersEnabled,
                joystickVisible,
                languageCode);
    }
}
