package com.nayon.api.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StoreOpenApiContractTest {

    @Test
    void catalogAndGooglePlayVerificationContractStayPublished() throws Exception {
        String openApi = Files.readString(
                Path.of("src/main/resources/openapi/nayon-api-v1.yaml"));

        assertThat(openApi)
                .contains("/store/catalog:")
                .contains("operationId: getStoreCatalog")
                .contains("/store/purchases/google-play/verify:")
                .contains("operationId: verifyGooglePlayPurchase")
                .contains("/store/first-purchase-reward:")
                .contains("operationId: getFirstPurchaseReward")
                .contains("StoreCatalogResponse:")
                .contains("GooglePlayPurchaseVerifyRequest:")
                .contains("StorePurchaseResponse:")
                .contains("type: [string, 'null']")
                .contains("enum: [DIRECT_CURRENCY, LIMITED_BENEFIT, null]")
                .contains("FirstPurchaseRewardResponse:")
                .contains("enum: [PENDING_VERIFICATION, REJECTED, GRANTED]")
                .doesNotContain("enum: [PENDING_VERIFICATION, REJECTED, GRANTED, CONSUMED]")
                .contains("$ref: '#/components/parameters/IdempotencyKey'");
    }
}
