package com.nayon.api.interfaces;

import com.nayon.api.economy.EconomySnapshot;
import com.nayon.api.time.RewardPeriod;
import com.nayon.api.weeklygift.WeeklyGiftRepository;
import com.nayon.api.weeklygift.WeeklyGiftReward;
import com.nayon.api.weeklygift.WeeklyGiftState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
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
        WeeklyGiftContractTest.WeeklyGiftFake.class})
class WeeklyGiftContractTest {
    @Autowired MockMvc mvc;

    @Test
    void stateRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me/weekly-gift"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checkInReturnsKstWeeklyState() throws Exception {
        mvc.perform(post("/api/v1/me/weekly-gift/check-in")
                        .with(player("weekly-check-in")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverTime").value(
                        org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.zoneId").value("Asia/Seoul"))
                .andExpect(jsonPath("$.loginDays").value(1))
                .andExpect(jsonPath("$.requiredLoginDays").value(3))
                .andExpect(jsonPath("$.claimable").value(false));
    }

    @Test
    void claimRequiresIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/me/weekly-gift/claim")
                        .with(player("weekly-no-key")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void claimReturnsRewardAndEconomy() throws Exception {
        mvc.perform(post("/api/v1/me/weekly-gift/claim")
                        .with(player("weekly-claim"))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(true))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.reward.assetCode").value("DIAMOND"))
                .andExpect(jsonPath("$.reward.amount").value(1))
                .andExpect(jsonPath("$.economy.currencies.DIAMOND").value(1));
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor player(
            String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("nayon:provider", "GOOGLE")
                .claim("token_use", "access")
                .claim("client_id", "nayon-unity-client"));
    }

    @TestConfiguration
    static class WeeklyGiftFake {
        @Bean
        @Primary
        WeeklyGiftRepository weeklyGiftRepository() {
            return new WeeklyGiftRepository() {
                @Override
                public WeeklyGiftState get(
                        UUID accountId, RewardPeriod period, Instant now) {
                    return state(accountId, period, now, 0, false, null, null);
                }

                @Override
                public WeeklyGiftState checkIn(
                        UUID accountId, RewardPeriod period,
                        LocalDate loginDate, Instant now) {
                    return state(accountId, period, now, 1, false, null, null);
                }

                @Override
                public WeeklyGiftState claim(
                        UUID accountId, UUID requestId,
                        RewardPeriod period, Instant now) {
                    var reward = new WeeklyGiftReward("CURRENCY", "DIAMOND", 1);
                    var economy = new EconomySnapshot(
                            accountId, Map.of("DIAMOND", 1L), Map.of(), List.of(), true);
                    return state(accountId, period, now, 3, true, reward, economy);
                }

                private WeeklyGiftState state(
                        UUID accountId, RewardPeriod period, Instant now,
                        int days, boolean claimed, WeeklyGiftReward reward,
                        EconomySnapshot economy) {
                    return WeeklyGiftState.create(
                            now.atZone(com.nayon.api.time.KstGameTimeCalculator.KST),
                            period, days, claimed, reward, economy, false);
                }
            };
        }
    }
}
