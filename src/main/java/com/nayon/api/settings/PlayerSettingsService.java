package com.nayon.api.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
public class PlayerSettingsService {

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "ko", "en", "ja", "zh-Hans", "zh-Hant", "th", "vi", "id",
            "es", "pt", "de", "fr", "ru", "ar", "tr");

    private final PlayerSettingsRepository repository;

    public PlayerSettingsService(PlayerSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PlayerSettings get(UUID accountId) {
        return repository.findByAccountId(accountId)
                .orElseGet(() -> PlayerSettings.defaults(accountId));
    }

    @Transactional
    public PlayerSettings patch(UUID accountId, PlayerSettingsPatch patch) {
        if (patch == null || patch.isEmpty()) {
            throw new IllegalArgumentException("At least one setting is required");
        }
        if (patch.languageCode() != null
                && !SUPPORTED_LANGUAGES.contains(patch.languageCode())) {
            throw new IllegalArgumentException("Unsupported language");
        }
        return repository.upsert(accountId, patch);
    }
}
