package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.subscription.SubscriptionService;
import com.nayon.api.subscription.SubscriptionVerificationResult;
import com.nayon.api.time.ServerClock;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SubscriptionController {

    private final CurrentAccountResolver accountResolver;
    private final SubscriptionService service;
    private final ServerClock clock;

    public SubscriptionController(
            CurrentAccountResolver accountResolver,
            SubscriptionService service,
            ServerClock clock) {
        this.accountResolver = accountResolver;
        this.service = service;
        this.clock = clock;
    }

    @GetMapping("/subscriptions/catalog")
    public SubscriptionCatalogResponse catalog(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String platform) {
        if (!"GOOGLE_PLAY".equals(platform)) {
            throw new IllegalArgumentException("Unsupported subscription platform");
        }
        PlayerAccount account = accountResolver.resolve(jwt);
        return SubscriptionCatalogResponse.from(service.catalog(account.id()));
    }

    @GetMapping("/me/subscriptions")
    public SubscriptionResponse.ListResponse subscriptions(
            @AuthenticationPrincipal Jwt jwt) {
        PlayerAccount account = accountResolver.resolve(jwt);
        Instant now = clock.now();
        return new SubscriptionResponse.ListResponse(
                now, service.findAll(account.id()).stream()
                .map(subscription -> SubscriptionResponse.from(subscription, now))
                .toList());
    }

    @PostMapping("/store/subscriptions/google-play/verify")
    public ResponseEntity<SubscriptionResponse.VerifyResponse> verify(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @Valid @RequestBody SubscriptionVerifyRequest request) {
        PlayerAccount account = accountResolver.resolve(jwt);
        SubscriptionVerificationResult result = service.verify(
                account.id(), requestId, request.productId(), request.purchaseToken());
        SubscriptionResponse.VerifyResponse response =
                SubscriptionResponse.VerifyResponse.from(result, clock.now());
        return result.replay()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(201).body(response);
    }
}
