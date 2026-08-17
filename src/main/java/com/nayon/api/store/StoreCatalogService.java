package com.nayon.api.store;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class StoreCatalogService {

    private final StoreCatalogRepository repository;
    private final StoreAccountHasher accountHasher;

    public StoreCatalogService(
            StoreCatalogRepository repository,
            StoreAccountHasher accountHasher) {
        this.repository = repository;
        this.accountHasher = accountHasher;
    }

    public StoreCatalog get(UUID accountId, StorePlatform platform) {
        return new StoreCatalog(
                platform,
                accountHasher.hash(accountId),
                repository.findActiveOffers(platform));
    }
}
