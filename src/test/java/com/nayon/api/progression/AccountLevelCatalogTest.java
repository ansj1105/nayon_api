package com.nayon.api.progression;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountLevelCatalogTest {

    private final AccountLevelCatalog catalog =
            new AccountLevelCatalog(new ObjectMapper());

    @Test
    void matchesUnityLevelThresholdsThroughGrowthFundMaximum() {
        assertThat(catalog.level(0)).isEqualTo(1);
        assertThat(catalog.level(249)).isEqualTo(1);
        assertThat(catalog.level(250)).isEqualTo(2);
        assertThat(catalog.level(1_449)).isEqualTo(4);
        assertThat(catalog.level(1_450)).isEqualTo(5);
        assertThat(catalog.level(167_499)).isEqualTo(49);
        assertThat(catalog.level(167_500)).isEqualTo(50);
        assertThat(catalog.level(Long.MAX_VALUE)).isEqualTo(50);
    }
}
