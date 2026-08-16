package com.nayon.api.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcPlayerSettingsRepository implements PlayerSettingsRepository {

    private static final String RETURNING = """
            returning account_id, effect_sound_enabled, background_music_enabled,
                      reduced_effects_enabled, reduced_critical_effects_enabled,
                      damage_numbers_enabled, joystick_visible, language_code, revision
            """;

    private final JdbcTemplate jdbc;

    public JdbcPlayerSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PlayerSettings> findByAccountId(UUID accountId) {
        List<PlayerSettings> values = jdbc.query("""
                select account_id, effect_sound_enabled, background_music_enabled,
                       reduced_effects_enabled, reduced_critical_effects_enabled,
                       damage_numbers_enabled, joystick_visible, language_code, revision
                  from player_settings
                 where account_id = ?
                """, this::map, accountId);
        return values.stream().findFirst();
    }

    @Override
    public PlayerSettings upsert(UUID accountId, PlayerSettingsPatch patch) {
        return jdbc.queryForObject("""
                insert into player_settings(
                    account_id, effect_sound_enabled, background_music_enabled,
                    reduced_effects_enabled, reduced_critical_effects_enabled,
                    damage_numbers_enabled, joystick_visible, language_code)
                values (?, coalesce(?, true), coalesce(?, true), coalesce(?, false),
                        coalesce(?, false), coalesce(?, true), coalesce(?, true),
                        coalesce(?, 'en'))
                on conflict (account_id) do update
                   set effect_sound_enabled = coalesce(?, player_settings.effect_sound_enabled),
                       background_music_enabled = coalesce(?, player_settings.background_music_enabled),
                       reduced_effects_enabled = coalesce(?, player_settings.reduced_effects_enabled),
                       reduced_critical_effects_enabled = coalesce(?, player_settings.reduced_critical_effects_enabled),
                       damage_numbers_enabled = coalesce(?, player_settings.damage_numbers_enabled),
                       joystick_visible = coalesce(?, player_settings.joystick_visible),
                       language_code = coalesce(?, player_settings.language_code),
                       revision = player_settings.revision + 1,
                       updated_at = now()
                """ + RETURNING,
                this::map,
                accountId,
                patch.effectSoundEnabled(),
                patch.backgroundMusicEnabled(),
                patch.reducedEffectsEnabled(),
                patch.reducedCriticalEffectsEnabled(),
                patch.damageNumbersEnabled(),
                patch.joystickVisible(),
                patch.languageCode(),
                patch.effectSoundEnabled(),
                patch.backgroundMusicEnabled(),
                patch.reducedEffectsEnabled(),
                patch.reducedCriticalEffectsEnabled(),
                patch.damageNumbersEnabled(),
                patch.joystickVisible(),
                patch.languageCode());
    }

    private PlayerSettings map(ResultSet rs, int rowNumber) throws SQLException {
        return new PlayerSettings(
                rs.getObject("account_id", UUID.class),
                rs.getBoolean("effect_sound_enabled"),
                rs.getBoolean("background_music_enabled"),
                rs.getBoolean("reduced_effects_enabled"),
                rs.getBoolean("reduced_critical_effects_enabled"),
                rs.getBoolean("damage_numbers_enabled"),
                rs.getBoolean("joystick_visible"),
                rs.getString("language_code"),
                rs.getLong("revision"));
    }
}
