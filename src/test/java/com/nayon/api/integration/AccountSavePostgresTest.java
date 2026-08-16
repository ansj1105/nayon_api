package com.nayon.api.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.save.CloudSave;
import com.nayon.api.save.CloudSaveService;
import com.nayon.api.save.IdempotencyConflictException;
import com.nayon.api.save.SaveContent;
import com.nayon.api.save.SaveRevisionConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "management.health.db.enabled=false")
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class AccountSavePostgresTest {

    @Autowired
    AccountService accountService;

    @Autowired
    CloudSaveService cloudSaveService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table gacha_draw_results, gacha_draws, gacha_pity_states, "
                + "economy_bootstraps, economy_ledger, "
                + "player_equipment, player_items, player_wallets, "
                + "save_imports, player_save_states, auth_identities, player_accounts");
    }

    @Test
    void identitiesAndSavesRemainIsolatedWithRevisionCompareAndSet() {
        PlayerAccount google = accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.GOOGLE, "subject-a"));
        PlayerAccount sameGoogle = accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.GOOGLE, "subject-a"));
        PlayerAccount apple = accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.APPLE, "subject-a"));

        assertThat(sameGoogle.id()).isEqualTo(google.id());
        assertThat(apple.id()).isNotEqualTo(google.id());

        SaveContent firstContent = content("a".repeat(64), "Google");
        CloudSave first = cloudSaveService.put(google.id(), 0, firstContent);

        assertThat(first.revision()).isEqualTo(1);
        assertThat(cloudSaveService.get(apple.id())).isEmpty();
        assertThat(cloudSaveService.get(google.id()).orElseThrow().payload()
                .get("nickname").asText()).isEqualTo("Google");

        CloudSave second = cloudSaveService.put(
                google.id(), 1, content("b".repeat(64), "Updated"));
        assertThat(second.revision()).isEqualTo(2);

        assertThatThrownBy(() -> cloudSaveService.put(
                google.id(), 1, content("c".repeat(64), "Stale")))
                .isInstanceOf(SaveRevisionConflictException.class);
    }

    @Test
    void importRetryReturnsStoredOriginalOutcomeAndChecksumCannotMoveAccounts() {
        PlayerAccount google = accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.GOOGLE, "import-a"));
        PlayerAccount apple = accountService.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.APPLE, "import-b"));
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        String checksum = "d".repeat(64);

        CloudSave imported = cloudSaveService.importInitial(
                google.id(), requestId, content(checksum, "Imported"));
        cloudSaveService.put(
                google.id(), 1, content("e".repeat(64), "Changed"));
        CloudSave replay = cloudSaveService.importInitial(
                google.id(), requestId, content(checksum, "Imported"));

        assertThat(replay).isEqualTo(imported);
        assertThat(replay.revision()).isEqualTo(1);

        assertThatThrownBy(() -> cloudSaveService.importInitial(
                apple.id(), UUID.randomUUID(), content(checksum, "Copied")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    private SaveContent content(String checksum, String nickname) {
        return new SaveContent(
                1,
                objectMapper.createObjectNode().put("nickname", nickname),
                checksum,
                "0.1.0-integration");
    }
}
