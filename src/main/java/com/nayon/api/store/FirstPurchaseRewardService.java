package com.nayon.api.store;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class FirstPurchaseRewardService {
    private final FirstPurchaseRewardRepository repository;

    public FirstPurchaseRewardService(FirstPurchaseRewardRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<FirstPurchaseReward> get(UUID accountId) {
        return repository.findByAccount(accountId);
    }
}
