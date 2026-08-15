package com.nayon.api.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.account.AccountRepository;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.save.CloudSave;
import com.nayon.api.save.CloudSaveRepository;
import com.nayon.api.save.SaveContent;
import com.nayon.api.save.SaveImportRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.health.db.enabled=false")
@AutoConfigureMockMvc
class SaveContractTest {

    private static final String CHECKSUM_A = "a".repeat(64);
    private static final String CHECKSUM_B = "b".repeat(64);

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void authenticatedPlayerCanCreateAndReadOwnSave() throws Exception {
        mvc.perform(put("/api/v1/save")
                        .with(player("GOOGLE", "subject-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeRequest(0, CHECKSUM_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.checksum").value(CHECKSUM_A));

        mvc.perform(get("/api/v1/save")
                        .with(player("GOOGLE", "subject-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.nickname").value("Nyaon"));
    }

    @Test
    void staleRevisionReturnsContractError() throws Exception {
        mvc.perform(put("/api/v1/save")
                        .with(player("APPLE", "subject-stale"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeRequest(0, CHECKSUM_A)))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/save")
                        .with(player("APPLE", "subject-stale"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeRequest(0, CHECKSUM_B)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAVE_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void importRequiresIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/save/import")
                        .with(player("GOOGLE", "subject-import"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importRequest(CHECKSUM_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void invalidChecksumIsRejectedAtHttpBoundary() throws Exception {
        mvc.perform(put("/api/v1/save")
                        .with(player("GOOGLE", "subject-invalid"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeRequest(0, "not-a-checksum")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void profileIsScopedToIdentityAndCanBeUpdated() throws Exception {
        mvc.perform(patch("/api/v1/me")
                        .with(player("GOOGLE", "profile-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nickname": "Miya",
                                  "avatarCode": "Avatar_02",
                                  "frameCode": "Frame_03"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Miya"));

        mvc.perform(get("/api/v1/me")
                        .with(player("GOOGLE", "profile-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Miya"));

        mvc.perform(get("/api/v1/me")
                        .with(player("APPLE", "profile-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value(
                        org.hamcrest.Matchers.not("Miya")));
    }

    private JwtRequestPostProcessor player(String provider, String subject) {
        return jwt().jwt(token -> token
                .subject(subject)
                .claim("nayon:provider", provider)
                .claim("token_use", "access")
                .claim("client_id", "nayon-unity-client"));
    }

    private String writeRequest(long expectedRevision, String checksum) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "expectedRevision", expectedRevision,
                "schemaVersion", 1,
                "payload", Map.of("nickname", "Nyaon"),
                "checksum", checksum,
                "clientBuild", "0.1.0-test"));
    }

    private String importRequest(String checksum) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "schemaVersion", 1,
                "payload", Map.of("nickname", "Nyaon"),
                "checksum", checksum,
                "clientBuild", "0.1.0-test"));
    }

    @TestConfiguration
    static class Fakes {

        @Bean
        @Primary
        PlatformTransactionManager transactionManager() {
            return new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                }

                @Override
                public void rollback(TransactionStatus status) {
                }
            };
        }

        @Bean
        @Primary
        AccountRepository accountRepository() {
            return new AccountRepository() {
                private final Map<AuthenticatedIdentity, PlayerAccount> accounts =
                        new HashMap<>();

                @Override
                public PlayerAccount resolveOrCreate(
                        AuthenticatedIdentity identity,
                        PlayerAccount proposedAccount) {
                    return accounts.computeIfAbsent(identity, ignored -> proposedAccount);
                }

                @Override
                public PlayerAccount updateProfile(
                        UUID accountId,
                        com.nayon.api.account.PlayerProfile profile) {
                    for (Map.Entry<AuthenticatedIdentity, PlayerAccount> entry
                            : accounts.entrySet()) {
                        PlayerAccount current = entry.getValue();
                        if (current.id().equals(accountId)) {
                            PlayerAccount updated = new PlayerAccount(
                                    current.id(),
                                    current.publicId(),
                                    current.status(),
                                    profile.nickname(),
                                    profile.avatarCode(),
                                    profile.frameCode(),
                                    current.locale(),
                                    current.createdAt());
                            entry.setValue(updated);
                            return updated;
                        }
                    }
                    throw new IllegalArgumentException("account does not exist");
                }
            };
        }

        @Bean
        @Primary
        CloudSaveRepository cloudSaveRepository() {
            return new CloudSaveRepository() {
                private final Map<UUID, CloudSave> saves = new HashMap<>();
                private final Map<UUID, SaveImportRecord> imports = new HashMap<>();

                @Override
                public Optional<CloudSave> findByAccountId(UUID accountId) {
                    return Optional.ofNullable(saves.get(accountId));
                }

                @Override
                public CloudSave create(UUID accountId, SaveContent content) {
                    CloudSave save = save(accountId, 1, content);
                    if (saves.putIfAbsent(accountId, save) != null) {
                        throw new com.nayon.api.save.SaveRevisionConflictException();
                    }
                    return save;
                }

                @Override
                public Optional<CloudSave> updateIfRevision(
                        UUID accountId, long expectedRevision, SaveContent content) {
                    CloudSave current = saves.get(accountId);
                    if (current == null || current.revision() != expectedRevision) {
                        return Optional.empty();
                    }
                    CloudSave save = save(accountId, expectedRevision + 1, content);
                    saves.put(accountId, save);
                    return Optional.of(save);
                }

                @Override
                public Optional<SaveImportRecord> findImport(UUID requestId) {
                    return Optional.ofNullable(imports.get(requestId));
                }

                @Override
                public CloudSave importInitial(
                        UUID accountId, UUID requestId, SaveContent content) {
                    CloudSave save = create(accountId, content);
                    imports.put(requestId,
                            new SaveImportRecord(accountId, requestId, content.checksum(), save));
                    return save;
                }

                private CloudSave save(
                        UUID accountId, long revision, SaveContent content) {
                    return new CloudSave(
                            accountId,
                            content.schemaVersion(),
                            revision,
                            content.payload(),
                            content.checksum(),
                            content.clientBuild(),
                            Instant.parse("2026-08-15T00:00:00Z"));
                }
            };
        }
    }
}
