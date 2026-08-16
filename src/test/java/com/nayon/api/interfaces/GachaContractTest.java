package com.nayon.api.interfaces;

import com.nayon.api.economy.EconomySnapshot;
import com.nayon.api.gacha.GachaAward;
import com.nayon.api.gacha.GachaBanner;
import com.nayon.api.gacha.GachaDrawResult;
import com.nayon.api.gacha.GachaHistoryPage;
import com.nayon.api.gacha.GachaPayment;
import com.nayon.api.gacha.GachaPity;
import com.nayon.api.gacha.GachaRepository;
import com.nayon.api.gacha.GachaSpec;
import com.nayon.api.gacha.GachaEngine;
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
        GachaContractTest.GachaFake.class})
class GachaContractTest {
    @Autowired MockMvc mvc;

    @Test
    void endpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/gacha/draws"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/gacha/draws")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void supportedDrawReturnsCreatedAndIdenticalRetryReturnsOk() throws Exception {
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000601");
        mvc.perform(post("/api/v1/gacha/draws")
                        .with(player("draw"))
                        .header("Idempotency-Key", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.banner").value("COMMON"))
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.replay").value(false));

        mvc.perform(post("/api/v1/gacha/draws")
                        .with(player("draw"))
                        .header("Idempotency-Key", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(true));
    }

    @Test
    void unsupportedCombinationAndUnknownFieldsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/gacha/draws")
                        .with(player("unsupported"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"banner":"COMMON","payment":"DIAMOND","count":10}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(post("/api/v1/gacha/draws")
                        .with(player("unknown"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"banner":"COMMON","payment":"SILVER_KEY","count":1,"seed":7}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedCursorAndLimitAreContractErrors() throws Exception {
        mvc.perform(get("/api/v1/gacha/draws?before=bad&limit=0")
                        .with(player("history")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private String validRequest() {
        return "{\"banner\":\"COMMON\",\"payment\":\"SILVER_KEY\",\"count\":1}";
    }

    private JwtRequestPostProcessor player(String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("nayon:provider", "GOOGLE")
                .claim("token_use", "access")
                .claim("client_id", "nayon-unity-client"));
    }

    @TestConfiguration
    static class GachaFake {
        @Bean
        @Primary
        GachaRepository gachaRepository() {
            return new GachaRepository() {
                private final Map<String, GachaDrawResult> requests = new HashMap<>();

                @Override
                public GachaDrawResult draw(
                        UUID accountId, UUID requestId, String requestHash,
                        GachaSpec spec, GachaEngine engine) {
                    String key = accountId + ":" + requestId;
                    GachaDrawResult previous = requests.get(key);
                    if (previous != null) return previous.asReplay();
                    GachaDrawResult result = new GachaDrawResult(
                            UUID.randomUUID(), spec.banner(), spec.payment(), spec.amount(),
                            List.of(new GachaAward(
                                    UUID.randomUUID(), "E41000", "COMMON", false)),
                            GachaPity.NONE,
                            new EconomySnapshot(accountId, Map.of(),
                                    Map.of("SILVER_KEY", 1L), List.of(), true),
                            Instant.parse("2026-08-16T00:00:00Z"), false);
                    requests.put(key, result);
                    return result;
                }

                @Override
                public GachaHistoryPage history(UUID accountId, UUID before, int limit) {
                    return new GachaHistoryPage(List.of(), null);
                }
            };
        }
    }
}
