package com.nayon.api.subscription;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionOpenApiContractTest {

    @Test
    void independentMonthlySubscriptionLifecycleStaysPublished() throws Exception {
        String openApi = Files.readString(
                Path.of("src/main/resources/openapi/nayon-api-v1.yaml"));

        assertThat(openApi)
                .contains("/subscriptions/catalog:")
                .contains("operationId: getSubscriptionCatalog")
                .contains("/me/subscriptions:")
                .contains("operationId: getMySubscriptions")
                .contains("/store/subscriptions/google-play/verify:")
                .contains("operationId: verifyGooglePlaySubscription")
                .contains("/public/google-play/rtdn:")
                .contains("operationId: receiveGooglePlayRtdn")
                .contains("/me/subscriptions/{planCode}/daily-reward/claim:")
                .contains("operationId: claimSubscriptionDailyReward")
                .contains("SubscriptionPlanCode:")
                .contains("enum: [MONTHLY_GROWTH, MONTHLY_ADVANCED]")
                .contains("SubscriptionState:")
                .contains("enum: [PENDING, ACTIVE, CANCELED, GRACE_PERIOD, ON_HOLD, PAUSED, EXPIRED, REVOKED]")
                .contains("SubscriptionVerifyRequest:")
                .contains("required: [productId, purchaseToken]")
                .contains("SubscriptionCatalogResponse:")
                .contains("SubscriptionListResponse:")
                .contains("GooglePlayRtdnPushRequest:")
                .contains("$ref: '#/components/parameters/IdempotencyKey'")
                .doesNotContain("clientPrice")
                .doesNotContain("clientRewardAmount")
                .doesNotContain("clientAccountLevel");
    }
}
