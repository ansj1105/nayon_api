package com.nayon.api.store;

import java.util.List;

public interface StoreCatalogRepository {
    List<StoreCatalogOffer> findActiveOffers(StorePlatform platform);
}
