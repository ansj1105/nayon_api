package com.nayon.api.integration;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.settings.PlayerSettings;
import com.nayon.api.settings.PlayerSettingsPatch;
import com.nayon.api.settings.PlayerSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "management.health.db.enabled=false")
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class PlayerSettingsPostgresTest {

    @Autowired
    AccountService accountService;

    @Autowired
    PlayerSettingsService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table player_account_link_rewards, player_korion_wallet_links, korion_wallet_link_requests, player_share_rewards, player_settings, "
                + "offline_battle_decisions, offline_battle_runs, "
                + "offline_battle_submissions, offline_play_window_requests, "
                + "offline_play_budgets, "
                + "battle_rewards, battle_anomalies, battle_completions, battle_sessions, "
                + "player_progression, gacha_draw_results, gacha_draws, gacha_pity_states, "
                + "economy_bootstraps, economy_ledger, player_equipment, player_items, "
                + "player_wallets, save_imports, player_save_states, auth_identities, "
                + "player_accounts");
    }

    @Test
    void patchPersistsPartialValuesAndKeepsAccountsIsolated() {
        PlayerAccount first = account("settings-a");
        PlayerAccount second = account("settings-b");

        PlayerSettings firstUpdate = service.patch(first.id(), new PlayerSettingsPatch(
                false, null, null, null, null, null, "ko"));
        PlayerSettings secondUpdate = service.patch(second.id(), new PlayerSettingsPatch(
                null, null, null, null, false, null, null));
        PlayerSettings firstAgain = service.patch(first.id(), new PlayerSettingsPatch(
                null, null, true, null, null, null, null));

        assertThat(firstUpdate.revision()).isEqualTo(1);
        assertThat(firstAgain.revision()).isEqualTo(2);
        assertThat(firstAgain.effectSoundEnabled()).isFalse();
        assertThat(firstAgain.reducedEffectsEnabled()).isTrue();
        assertThat(firstAgain.languageCode()).isEqualTo("ko");
        assertThat(secondUpdate.damageNumbersEnabled()).isFalse();
        assertThat(service.get(second.id()).effectSoundEnabled()).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from player_settings", Long.class)).isEqualTo(2L);
    }

    private PlayerAccount account(String subject) {
        return accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.GOOGLE, subject));
    }
}
