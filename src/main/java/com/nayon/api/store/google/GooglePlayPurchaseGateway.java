package com.nayon.api.store.google;

public interface GooglePlayPurchaseGateway {
    GooglePlayPurchase get(String purchaseToken);
}
