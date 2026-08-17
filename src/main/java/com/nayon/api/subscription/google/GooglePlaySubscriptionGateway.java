package com.nayon.api.subscription.google;

public interface GooglePlaySubscriptionGateway {
    GooglePlaySubscription get(String purchaseToken);
}
