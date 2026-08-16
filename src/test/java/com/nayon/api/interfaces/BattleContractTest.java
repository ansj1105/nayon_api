package com.nayon.api.interfaces;

import com.nayon.api.battle.BattleAnomalyEvaluator;
import com.nayon.api.battle.BattleCompletionCommand;
import com.nayon.api.battle.BattleCompletionResult;
import com.nayon.api.battle.BattleHistoryPage;
import com.nayon.api.battle.BattleRepository;
import com.nayon.api.battle.BattleRewardState;
import com.nayon.api.battle.BattleSessionResult;
import com.nayon.api.battle.BattleStageCatalog;
import com.nayon.api.battle.BattleStageLimit;
import com.nayon.api.battle.BattleStartCommand;
import com.nayon.api.economy.EconomySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.health.db.enabled=false")
@AutoConfigureMockMvc
@Import({SaveContractTest.Fakes.class, EconomyContractTest.EconomyFake.class,
        GachaContractTest.GachaFake.class, BattleContractTest.BattleFake.class})
class BattleContractTest {
    @Autowired MockMvc mvc;

    @Test
    void battleEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/battles"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/battles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void startAndCompletionHaveStableReplayContract() throws Exception {
        UUID startKey = UUID.randomUUID();
        String body = mvc.perform(post("/api/v1/battles")
                        .with(player("battle"))
                        .header("Idempotency-Key", startKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replay").value(false))
                .andReturn().getResponse().getContentAsString();
        UUID battleId = UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(body).get("battleId").asText());

        UUID completionKey = UUID.randomUUID();
        mvc.perform(post("/api/v1/battles/{id}/complete", battleId)
                        .with(player("battle"))
                        .header("Idempotency-Key", completionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rewardState").value("GRANTED"))
                .andExpect(jsonPath("$.replay").value(false));
        mvc.perform(post("/api/v1/battles/{id}/complete", battleId)
                        .with(player("battle"))
                        .header("Idempotency-Key", completionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(true));
    }

    @Test
    void unknownFieldsAndMalformedIdsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/battles")
                        .with(player("unknown"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stageCode\":\"STAGE_1\",\"clientBuild\":\"test\",\"trusted\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/battles/not-a-uuid/complete")
                        .with(player("bad-id"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeRequest()))
                .andExpect(status().isBadRequest());
    }

    private String startRequest() {
        return "{\"stageCode\":\"STAGE_1\",\"clientBuild\":\"test\"}";
    }

    private String completeRequest() {
        return "{\"outcome\":\"CLEAR\",\"elapsedSeconds\":10,\"killCount\":100,"
                + "\"totalDamage\":1000,\"reachedWave\":16,"
                + "\"clientEndedAt\":\"2026-08-16T00:00:10Z\"}";
    }

    private JwtRequestPostProcessor player(String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("nayon:provider", "GOOGLE")
                .claim("token_use", "access")
                .claim("client_id", "nayon-unity-client"));
    }

    @TestConfiguration
    static class BattleFake {
        @Bean
        @Primary
        BattleRepository battleRepository() {
            return new BattleRepository() {
                private final Map<String, BattleSessionResult> starts = new HashMap<>();
                private final Map<UUID, BattleCompletionResult> completions = new HashMap<>();

                @Override
                public BattleSessionResult start(
                        UUID accountId, UUID requestId, String requestHash,
                        BattleStartCommand command, BattleStageLimit stage,
                        BattleStageCatalog.Configuration configuration, Instant now) {
                    String key = accountId + ":" + requestId;
                    BattleSessionResult previous = starts.get(key);
                    if (previous != null) return previous.asReplay();
                    BattleSessionResult result = new BattleSessionResult(
                            UUID.randomUUID(), command.stageCode(), configuration.version(),
                            now, now.plusSeconds(configuration.sessionTtlSeconds()), false);
                    starts.put(key, result);
                    return result;
                }

                @Override
                public BattleCompletionResult complete(
                        UUID accountId, UUID battleId, UUID requestId,
                        String requestHash, BattleCompletionCommand command,
                        BattleAnomalyEvaluator evaluator,
                        BattleStageCatalog.Configuration configuration, Instant now) {
                    BattleCompletionResult previous = completions.get(battleId);
                    if (previous != null) return previous.asReplay();
                    BattleCompletionResult result = new BattleCompletionResult(
                            battleId, "STAGE_1", command.outcome(),
                            BattleRewardState.GRANTED, 1000, 5, 5, 10, 1,
                            List.of(), new EconomySnapshot(
                                    accountId, Map.of("GOLD", 1000L), Map.of(),
                                    List.of(), true), now, false);
                    completions.put(battleId, result);
                    return result;
                }

                @Override
                public BattleHistoryPage history(UUID accountId, UUID before, int limit) {
                    return new BattleHistoryPage(List.of(), null);
                }
            };
        }
    }
}
