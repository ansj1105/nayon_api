package com.nayon.api.weeklygift;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyGiftOpenApiContractTest {

    @Test
    void publishesWeeklyGiftStateCheckInAndClaimContract() throws Exception {
        String openApi = Files.readString(
                Path.of("src/main/resources/openapi/nayon-api-v1.yaml"));

        assertThat(openApi)
                .contains("/me/weekly-gift:")
                .contains("operationId: getWeeklyGift")
                .contains("/me/weekly-gift/check-in:")
                .contains("operationId: checkInWeeklyGift")
                .contains("/me/weekly-gift/claim:")
                .contains("operationId: claimWeeklyGift")
                .contains("$ref: '#/components/parameters/IdempotencyKey'")
                .contains("WeeklyGiftResponse:")
                .contains("required: [serverTime, zoneId, weekStart, nextResetAt, loginDays, requiredLoginDays, claimable, claimEnabled, claimed]")
                .contains("example: '2026-08-19T12:00:00+09:00'")
                .contains("example: '2026-08-24T00:00:00+09:00'")
                .contains("WEEKLY_GIFT_NOT_ELIGIBLE")
                .contains("WEEKLY_GIFT_REWARD_NOT_CONFIGURED")
                .contains("'401':")
                .contains("'409':")
                .contains("'503':");
    }
}
