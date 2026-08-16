package com.nayon.api.settings;

public record PlayerSettingsPatch(
        Boolean effectSoundEnabled,
        Boolean backgroundMusicEnabled,
        Boolean reducedEffectsEnabled,
        Boolean reducedCriticalEffectsEnabled,
        Boolean damageNumbersEnabled,
        Boolean joystickVisible,
        String languageCode) {

    public static PlayerSettingsPatch empty() {
        return new PlayerSettingsPatch(null, null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return effectSoundEnabled == null
                && backgroundMusicEnabled == null
                && reducedEffectsEnabled == null
                && reducedCriticalEffectsEnabled == null
                && damageNumbersEnabled == null
                && joystickVisible == null
                && languageCode == null;
    }

    public PlayerSettings applyTo(PlayerSettings current) {
        return new PlayerSettings(
                current.accountId(),
                effectSoundEnabled != null ? effectSoundEnabled : current.effectSoundEnabled(),
                backgroundMusicEnabled != null
                        ? backgroundMusicEnabled : current.backgroundMusicEnabled(),
                reducedEffectsEnabled != null
                        ? reducedEffectsEnabled : current.reducedEffectsEnabled(),
                reducedCriticalEffectsEnabled != null
                        ? reducedCriticalEffectsEnabled : current.reducedCriticalEffectsEnabled(),
                damageNumbersEnabled != null
                        ? damageNumbersEnabled : current.damageNumbersEnabled(),
                joystickVisible != null ? joystickVisible : current.joystickVisible(),
                languageCode != null ? languageCode : current.languageCode(),
                current.revision() + 1);
    }
}
