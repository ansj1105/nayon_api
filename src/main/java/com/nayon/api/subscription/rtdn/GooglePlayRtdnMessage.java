package com.nayon.api.subscription.rtdn;

public record GooglePlayRtdnMessage(
        String messageId,
        String packageName,
        int notificationType,
        String purchaseToken) {
}
