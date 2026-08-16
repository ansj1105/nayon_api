package com.nayon.api.interfaces;

import com.nayon.api.settings.PlayerSettings;
import com.nayon.api.settings.PlayerSettingsPatch;
import com.nayon.api.settings.PlayerSettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.health.db.enabled=false")
@AutoConfigureMockMvc
@Import({SaveContractTest.Fakes.class, PlayerSettingsContractTest.SettingsFake.class})
class PlayerSettingsContractTest {

    @org.springframework.beans.factory.annotation.Autowired
    MockMvc mvc;

    @Test
    void settingsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me/settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void newAccountGetsDefaults() throws Exception {
        mvc.perform(get("/api/v1/me/settings").with(player("defaults")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectSoundEnabled").value(true))
                .andExpect(jsonPath("$.backgroundMusicEnabled").value(true))
                .andExpect(jsonPath("$.reducedEffectsEnabled").value(false))
                .andExpect(jsonPath("$.damageNumbersEnabled").value(true))
                .andExpect(jsonPath("$.languageCode").value("en"))
                .andExpect(jsonPath("$.revision").value(0));
    }

    @Test
    void partialPatchPreservesOtherFields() throws Exception {
        mvc.perform(patch("/api/v1/me/settings")
                        .with(player("partial"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"effectSoundEnabled":false,"languageCode":"ko"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectSoundEnabled").value(false))
                .andExpect(jsonPath("$.languageCode").value("ko"))
                .andExpect(jsonPath("$.revision").value(1));

        mvc.perform(patch("/api/v1/me/settings")
                        .with(player("partial"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"damageNumbersEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectSoundEnabled").value(false))
                .andExpect(jsonPath("$.damageNumbersEnabled").value(false))
                .andExpect(jsonPath("$.languageCode").value("ko"))
                .andExpect(jsonPath("$.revision").value(2));
    }

    @Test
    void emptyPatchAndUnsupportedLanguageAreRejected() throws Exception {
        mvc.perform(patch("/api/v1/me/settings")
                        .with(player("empty"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(patch("/api/v1/me/settings")
                        .with(player("null"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(patch("/api/v1/me/settings")
                        .with(player("language"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"languageCode\":\"xx\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private JwtRequestPostProcessor player(String subject) {
        return jwt().jwt(token -> token
                .subject(subject)
                .claim("nayon:provider", "GOOGLE")
                .claim("token_use", "access")
                .claim("client_id", "nayon-unity-client"));
    }

    @TestConfiguration
    static class SettingsFake {

        @Bean
        @Primary
        PlayerSettingsRepository playerSettingsRepository() {
            return new PlayerSettingsRepository() {
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
            };
        }
    }
}
