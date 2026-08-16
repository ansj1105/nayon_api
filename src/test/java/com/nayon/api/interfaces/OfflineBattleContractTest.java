package com.nayon.api.interfaces;

import com.nayon.api.battle.BattleRewardState;
import com.nayon.api.battle.BattleStageCatalog;
import com.nayon.api.battle.offline.OfflineBattleEvaluator;
import com.nayon.api.battle.offline.OfflineBattleRepository;
import com.nayon.api.battle.offline.OfflineBattleSubmissionCommand;
import com.nayon.api.battle.offline.OfflineBattleSubmissionResult;
import com.nayon.api.battle.offline.OfflineBattleWindowResult;
import com.nayon.api.economy.EconomySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.time.Instant;
import java.util.Map;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.health.db.enabled=false")
@AutoConfigureMockMvc
@Import({SaveContractTest.Fakes.class, EconomyContractTest.EconomyFake.class,
        GachaContractTest.GachaFake.class, BattleContractTest.BattleFake.class,
        OfflineBattleContractTest.OfflineBattleFake.class})
class OfflineBattleContractTest {
    @Autowired MockMvc mvc;

    @Test
    void submissionRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/offline-battles")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void syncIssuesServerTimedWindow() throws Exception {
        mvc.perform(post("/api/v1/offline-battles/sync")
                        .with(jwt().jwt(token -> token.subject("offline-sync")
                                .claim("nayon:provider", "GOOGLE")
                                .claim("token_use", "access")
                                .claim("client_id", "nayon-unity-client")))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.windowId").isNotEmpty())
                .andExpect(jsonPath("$.openedAt").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void submissionReturnsStableDecisionContract() throws Exception {
        mvc.perform(post("/api/v1/offline-battles")
                        .with(jwt().jwt(token -> token.subject("offline-player")
                                .claim("nayon:provider", "GOOGLE")
                                .claim("token_use", "access")
                                .claim("client_id", "nayon-unity-client")))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rewardState").value("GRANTED"))
                .andExpect(jsonPath("$.acceptedRunIds[0]").value(
                        "00000000-0000-0000-0000-000000000101"))
                .andExpect(jsonPath("$.totalAccountExp").value(487))
                .andExpect(jsonPath("$.replay").value(false));
    }

    @Test
    void negativeMetricsAndUnknownFieldsAreRejected() throws Exception {
        String invalid = request().replace("\"elapsedSeconds\":300",
                "\"elapsedSeconds\":-1");
        mvc.perform(post("/api/v1/offline-battles")
                        .with(jwt())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/offline-battles")
                        .with(jwt())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request().replace("}", ",\"trusted\":true}")))
                .andExpect(status().isBadRequest());
    }

    private String request() {
        return "{\"windowId\":\"00000000-0000-0000-0000-000000000001\",\"runs\":[{"
                + "\"runId\":\"00000000-0000-0000-0000-000000000101\","
                + "\"stageCode\":\"STAGE_1\",\"outcome\":\"CLEAR\","
                + "\"elapsedSeconds\":300,\"killCount\":100,"
                + "\"totalDamage\":1000,\"reachedWave\":16}]}";
    }

    @TestConfiguration
    static class OfflineBattleFake {
        @Bean
        @Primary
        OfflineBattleRepository offlineBattleRepository() {
            return new OfflineBattleRepository() {
                @Override
                public OfflineBattleWindowResult sync(
                        UUID accountId, UUID requestId, String requestHash,
                        Instant now, Instant expiresAt,
                        BattleStageCatalog.Configuration rules) {
                    return new OfflineBattleWindowResult(
                            UUID.randomUUID(), now, expiresAt, false);
                }

                @Override
                public OfflineBattleSubmissionResult submit(
                        UUID accountId, UUID requestId, String requestHash,
                        OfflineBattleSubmissionCommand command,
                        OfflineBattleEvaluator evaluator,
                        Instant now) {
                    return new OfflineBattleSubmissionResult(
                            UUID.randomUUID(), BattleRewardState.GRANTED,
                            command.runs().stream().map(run -> run.runId()).toList(),
                            List.of(), 187L, 487L, new EconomySnapshot(
                                    accountId, Map.of("GOLD", 1000L), Map.of(),
                                    List.of(), true), false);
                }
            };
        }
    }
}
