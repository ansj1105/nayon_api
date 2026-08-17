package com.nayon.api.store;

import java.util.List;

public record StoreCatalog(
        StorePlatform platform,
        String obfuscatedAccountId,
        List<StoreCatalogOffer> offers) {
}
