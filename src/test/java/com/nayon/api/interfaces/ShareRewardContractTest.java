package com.nayon.api.interfaces;

import com.nayon.api.share.ShareRewardRepository;
import com.nayon.api.share.ShareRewardState;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.health.db.enabled=false")
@AutoConfigureMockMvc
@Import({SaveContractTest.Fakes.class, EconomyContractTest.EconomyFake.class,
        ShareRewardContractTest.ShareFake.class})
class ShareRewardContractTest {

    @org.springframework.beans.factory.annotation.Autowired
    MockMvc mvc;

    @Test
    void shareRewardRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me/share-reward"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void frontendStateMovesFromShareToClaimToComplete() throws Exception {
        JwtRequestPostProcessor player = player("share-flow");

        mvc.perform(get("/api/v1/me/share-reward").with(player))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shared").value(false))
                .andExpect(jsonPath("$.canShare").value(true))
                .andExpect(jsonPath("$.canClaim").value(false));

        mvc.perform(post("/api/v1/economy/bootstrap")
                        .with(player("share-flow"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currencies":{"DIAMOND":100},"items":{},"equipment":[]}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/me/share-reward/share-opened")
                        .with(player("share-flow"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"com.kakao.talk\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shared").value(true))
                .andExpect(jsonPath("$.canShare").value(false))
                .andExpect(jsonPath("$.canClaim").value(true));

        mvc.perform(post("/api/v1/me/share-reward/claim")
                        .with(player("share-flow"))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewardClaimed").value(true))
                .andExpect(jsonPath("$.canClaim").value(false))
                .andExpect(jsonPath("$.economy.currencies.DIAMOND").value(150));

        mvc.perform(post("/api/v1/me/share-reward/claim")
                        .with(player("share-flow"))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.economy.currencies.DIAMOND").value(150));
    }

    @Test
    void claimBeforeShareAndMalformedKeyAreRejected() throws Exception {
        mvc.perform(post("/api/v1/me/share-reward/claim")
                        .with(player("required"))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHARE_REQUIRED"));

        mvc.perform(post("/api/v1/me/share-reward/claim")
                        .with(player("bad-key"))
                        .header("Idempotency-Key", "bad"))
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
    static class ShareFake {
        @Bean
        @Primary
        ShareRewardRepository shareRewardRepository() {
            return new ShareRewardRepository() {
                private final Map<UUID, ShareRewardState> states = new HashMap<>();

                @Override
                public Optional<ShareRewardState> findByAccountId(UUID accountId) {
                    return Optional.ofNullable(states.get(accountId));
                }

                @Override
                public ShareRewardState markOpened(UUID accountId, String target) {
                    return states.compute(accountId, (ignored, current) -> current == null
                            ? ShareRewardState.opened(
                                    accountId, UUID.randomUUID(), target)
                            : current.open(target));
                }

                @Override
                public ShareRewardState lockOrCreate(UUID accountId) {
                    return states.computeIfAbsent(accountId, ShareRewardState::initialPersisted);
                }

                @Override
                public ShareRewardState markClaimed(UUID accountId) {
                    ShareRewardState claimed = states.get(accountId).claim();
                    states.put(accountId, claimed);
                    return claimed;
                }
            };
        }
    }
}
