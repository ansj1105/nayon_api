package com.nayon.api.subscription.rtdn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.subscription.SubscriptionException;
import com.nayon.api.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GooglePlayRtdnServiceTest {

    @Test
    void authenticatesDeduplicatesAndReconcilesFromGoogle() {
        GooglePlayRtdnAuthenticator authenticator =
                mock(GooglePlayRtdnAuthenticator.class);
        GooglePlayRtdnRepository repository = mock(GooglePlayRtdnRepository.class);
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        GooglePlayRtdnService service = service(
                authenticator, repository, subscriptions);
        GooglePlayRtdnPushRequest request = request(
                "message-1", "com.korion.Nayon", "token-rtdn", 2);
        when(repository.begin(any(), any())).thenReturn(true, false);

        service.receive("Bearer valid", request);
        service.receive("Bearer valid", request);

        verify(authenticator, org.mockito.Mockito.times(2))
                .authenticate("Bearer valid");
        verify(subscriptions).reconcileByToken("token-rtdn");
        verify(repository).finish("message-1", "PROCESSED", "UPDATED");
    }

    @Test
    void rejectsWrongPackageBeforeCreatingEvent() {
        GooglePlayRtdnAuthenticator authenticator =
                mock(GooglePlayRtdnAuthenticator.class);
        GooglePlayRtdnRepository repository = mock(GooglePlayRtdnRepository.class);
        SubscriptionService subscriptions = mock(SubscriptionService.class);

        assertThatThrownBy(() -> service(authenticator, repository, subscriptions)
                .receive("Bearer valid", request(
                        "message-wrong", "other.package", "token", 2)))
                .isInstanceOfSatisfying(GooglePlayRtdnException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(
                                exception.code())
                                .isEqualTo("GOOGLE_PLAY_RTDN_PACKAGE_MISMATCH"));
        verify(repository, never()).begin(any(), any());
    }

    @Test
    void recordsRetryableFailureForUnknownToken() {
        GooglePlayRtdnAuthenticator authenticator =
                mock(GooglePlayRtdnAuthenticator.class);
        GooglePlayRtdnRepository repository = mock(GooglePlayRtdnRepository.class);
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        when(repository.begin(any(), any())).thenReturn(true);
        when(subscriptions.reconcileByToken("unknown-token"))
                .thenThrow(new SubscriptionException(
                        "SUBSCRIPTION_TOKEN_NOT_FOUND", "missing"));

        assertThatThrownBy(() -> service(authenticator, repository, subscriptions)
                .receive("Bearer valid", request(
                        "message-retry", "com.korion.Nayon", "unknown-token", 2)))
                .isInstanceOfSatisfying(GooglePlayRtdnException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(
                                exception.retryable()).isTrue());
        verify(repository).finish(
                "message-retry", "RETRYABLE_FAILED", "SUBSCRIPTION_TOKEN_NOT_FOUND");
    }

    @Test
    void rejectsMalformedBase64AndNotificationType() {
        GooglePlayRtdnAuthenticator authenticator =
                mock(GooglePlayRtdnAuthenticator.class);
        GooglePlayRtdnRepository repository = mock(GooglePlayRtdnRepository.class);
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        GooglePlayRtdnService service = service(
                authenticator, repository, subscriptions);

        assertThatThrownBy(() -> service.receive("Bearer valid",
                new GooglePlayRtdnPushRequest(
                        new GooglePlayRtdnPushRequest.Message("bad", "%%%"), null)))
                .isInstanceOf(GooglePlayRtdnException.class);
        assertThatThrownBy(() -> service.receive("Bearer valid",
                request("bad-type", "com.korion.Nayon", "token", 23)))
                .isInstanceOf(GooglePlayRtdnException.class);
    }

    private GooglePlayRtdnService service(
            GooglePlayRtdnAuthenticator authenticator,
            GooglePlayRtdnRepository repository,
            SubscriptionService subscriptions) {
        return new GooglePlayRtdnService(
                authenticator, repository, subscriptions,
                new ObjectMapper(), "com.korion.Nayon");
    }

    private GooglePlayRtdnPushRequest request(
            String messageId, String packageName,
            String purchaseToken, int notificationType) {
        String json = """
                {"version":"1.0","packageName":"%s",
                 "subscriptionNotification":{"version":"1.0",
                 "notificationType":%d,"purchaseToken":"%s",
                 "subscriptionId":"nayon.monthly.growth"}}
                """.formatted(packageName, notificationType, purchaseToken);
        return new GooglePlayRtdnPushRequest(
                new GooglePlayRtdnPushRequest.Message(
                        messageId, Base64.getEncoder().encodeToString(
                        json.getBytes(StandardCharsets.UTF_8))), null);
    }
}
