package com.nayon.api.interfaces;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LimitedBenefitContractTest {

    @Test
    void openApiPublishesDailyCampaignAndExactOnceClaimContract() throws IOException {
        String yaml;
        try (var stream = getClass().getResourceAsStream("/openapi/nayon-api-v1.yaml")) {
            yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(yaml)
                .contains("/events/limited-benefits/current:")
                .contains("operationId: getCurrentLimitedBenefit")
                .contains("/events/limited-benefits/offers/{offerCode}/claims:")
                .contains("operationId: claimLimitedBenefitOffer")
                .contains("/events/limited-benefits/offers/{offerCode}/ad-sessions:")
                .contains("operationId: createLimitedBenefitAdSession")
                .contains("/public/admob/rewarded-callback:")
                .contains("operationId: acceptAdMobRewardedCallback")
                .contains("LimitedBenefitCampaignResponse:")
                .contains("LimitedBenefitOffer:")
                .contains("LimitedBenefitClaimRequest:")
                .contains("LimitedBenefitClaimResponse:")
                .contains("LimitedBenefitAdSessionResponse:")
                .contains("[LOCKED, AVAILABLE, PROVIDER_UNAVAILABLE, CLAIMED]")
                .contains("'204':")
                .contains("'409':")
                .contains("'422':")
                .contains("'503':");
    }
}
