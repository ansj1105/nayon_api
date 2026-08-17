package com.nayon.api.integration;

import com.nayon.api.store.StoreCatalog;
import com.nayon.api.store.StoreCatalogService;
import com.nayon.api.store.StorePlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "management.health.db.enabled=false",
                "nayon.store.account-hash-key=test-only-account-hash-key"
        })
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class StoreCatalogPostgresTest {

    @Autowired StoreCatalogService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table player_limited_benefit_claims, admob_reward_callbacks, limited_benefit_ad_sessions, player_first_purchase_rewards, store_purchase_receipts, "
                + "store_product_versions, store_products");
    }

    @Test
    void exposesOnlyActiveAndCurrentlyValidProductVersionInOfferOrder() {
        UUID productId = UUID.randomUUID();
        jdbc.update("""
                insert into store_products(
                    id, offer_id, platform, store_product_id, product_type, active)
                select ?, id, 'GOOGLE_PLAY', 'nayon.diamond.600', 'ONE_TIME', true
                  from store_offers where offer_code = 'diamond_600'
                """, productId);
        jdbc.update("""
                insert into store_product_versions(
                    id, product_id, version, reward_asset_type, reward_asset_code,
                    reward_amount, valid_from, active)
                values (?, ?, 3, 'CURRENCY', 'DIAMOND', 650,
                        now() - interval '1 minute', true)
                """, UUID.randomUUID(), productId);

        StoreCatalog catalog = service.get(UUID.randomUUID(), StorePlatform.GOOGLE_PLAY);

        assertThat(catalog.offers()).singleElement().satisfies(offer -> {
            assertThat(offer.offerCode()).isEqualTo("diamond_600");
            assertThat(offer.productId()).isEqualTo("nayon.diamond.600");
            assertThat(offer.rewardAmount()).isEqualTo(650L);
            assertThat(offer.rewardVersion()).isEqualTo(3);
        });
    }
}
