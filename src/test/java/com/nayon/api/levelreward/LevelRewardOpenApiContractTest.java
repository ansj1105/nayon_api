package com.nayon.api.levelreward;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LevelRewardOpenApiContractTest {

    @Test
    void lifetimeOnceLevelRewardContractStaysPublished() throws Exception {
        String openApi = Files.readString(
                Path.of("src/main/resources/openapi/nayon-api-v1.yaml"));

        assertThat(openApi)
                .contains("/me/level-rewards:")
                .contains("operationId: getMyLevelRewards")
                .contains("/me/level-rewards/{trackCode}/{requiredLevel}/claim:")
                .contains("operationId: claimLevelReward")
                .contains("LevelRewardTrackCode:")
                .contains("enum: [FREE, PREMIUM, ROYAL]")
                .contains("LevelRewardCatalogItem:")
                .contains("LevelRewardListResponse:")
                .contains("LevelRewardClaimResponse:")
                .contains("format: int64")
                .contains("$ref: '#/components/parameters/IdempotencyKey'");
    }
}
