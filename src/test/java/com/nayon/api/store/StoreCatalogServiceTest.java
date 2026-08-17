package com.nayon.api.store;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreCatalogServiceTest {

    @Test
    void returnsRepositoryCatalogWithDeterministicAccountHash() {
        StoreCatalogRepository repository = platform -> List.of(new StoreCatalogOffer(
                "diamond_100", "nayon.diamond.100", "ONE_TIME",
                "DIAMOND", 100, 1));
        StoreAccountHasher hasher = new StoreAccountHasher("test-only-account-hash-key");
        StoreCatalogService service = new StoreCatalogService(repository, hasher);
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        StoreCatalog first = service.get(accountId, StorePlatform.GOOGLE_PLAY);
        StoreCatalog second = service.get(accountId, StorePlatform.GOOGLE_PLAY);

        assertThat(first.platform()).isEqualTo(StorePlatform.GOOGLE_PLAY);
        assertThat(first.obfuscatedAccountId())
                .hasSize(64)
                .isEqualTo(second.obfuscatedAccountId());
        assertThat(first.offers()).singleElement()
                .extracting(StoreCatalogOffer::offerCode,
                        StoreCatalogOffer::rewardAmount)
                .containsExactly("diamond_100", 100L);
    }

    @Test
    void rejectsHashingWhenSecretIsNotConfigured() {
        StoreCatalogService service = new StoreCatalogService(
                platform -> List.of(), new StoreAccountHasher(""));

        assertThatThrownBy(() -> service.get(UUID.randomUUID(), StorePlatform.GOOGLE_PLAY))
                .isInstanceOf(StoreConfigurationException.class)
                .hasMessageContaining("hash key");
    }
}
