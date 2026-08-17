package com.nayon.api.store;

import com.nayon.api.store.google.GooglePlayPurchase;
import com.nayon.api.store.google.GooglePlayPurchaseGateway;
import com.nayon.api.store.google.GooglePlayPurchaseState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorePurchaseServiceTest {

    @Test
    void grantsVerifiedPurchaseWithoutConsumingClientOwnedOrder() {
        UUID accountId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        StorePurchaseRepository repository = mock(StorePurchaseRepository.class);
        GooglePlayPurchaseGateway gateway = mock(GooglePlayPurchaseGateway.class);
        StoreAccountHasher accountHasher = mock(StoreAccountHasher.class);
        FirstPurchaseRewardRepository firstPurchaseRewardRepository =
                mock(FirstPurchaseRewardRepository.class);
        GooglePlayPurchase purchase = new GooglePlayPurchase(
                List.of("nayon.diamond.100"), GooglePlayPurchaseState.PURCHASED,
                "GPA.test", "account-hash", Instant.parse("2026-08-17T00:00:00Z"), false);
        when(accountHasher.hash(accountId)).thenReturn("account-hash");
        when(repository.begin(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(receipt(receiptId, accountId, requestId,
                        StorePurchaseState.PENDING_VERIFICATION, null));
        when(gateway.get("purchase-token")).thenReturn(purchase);
        when(repository.grant(receiptId, accountId, purchase))
                .thenReturn(receipt(receiptId, accountId, requestId,
                        StorePurchaseState.GRANTED, 100L));
        StorePurchaseService service = new StorePurchaseService(
                repository, gateway, accountHasher, firstPurchaseRewardRepository);

        StorePurchaseResult result = service.verify(
                accountId, requestId,
                new StorePurchaseCommand("nayon.diamond.100", "purchase-token"));

        assertThat(result.receipt().state()).isEqualTo(StorePurchaseState.GRANTED);
        assertThat(result.receipt().totalAssetBalance()).isEqualTo(100L);
    }

    private StorePurchaseReceipt receipt(
            UUID receiptId,
            UUID accountId,
            UUID requestId,
            StorePurchaseState state,
            Long totalBalance) {
        return new StorePurchaseReceipt(
                receiptId, accountId, requestId, "a".repeat(64), state,
                "diamond_100", "nayon.diamond.100", UUID.randomUUID(), 1,
                "DIRECT_CURRENCY", "DIAMOND", 100L, "purchase-token", "GPA.test",
                Instant.parse("2026-08-17T00:00:00Z"), totalBalance,
                null, null, state == StorePurchaseState.GRANTED ? Instant.now() : null,
                false);
    }
}
