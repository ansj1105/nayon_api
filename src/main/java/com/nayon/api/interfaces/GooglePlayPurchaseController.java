package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.store.StorePurchaseCommand;
import com.nayon.api.store.StorePurchaseResult;
import com.nayon.api.store.StorePurchaseService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/store/purchases/google-play")
public class GooglePlayPurchaseController {

    private final CurrentAccountResolver accountResolver;
    private final StorePurchaseService service;

    public GooglePlayPurchaseController(
            CurrentAccountResolver accountResolver,
            StorePurchaseService service) {
        this.accountResolver = accountResolver;
        this.service = service;
    }

    @PostMapping("/verify")
    public ResponseEntity<StorePurchaseResponse> verify(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @Valid @RequestBody GooglePlayPurchaseVerifyRequest request) {
        PlayerAccount account = accountResolver.resolve(jwt);
        StorePurchaseResult result = service.verify(
                account.id(), requestId,
                new StorePurchaseCommand(request.productId(), request.purchaseToken()));
        StorePurchaseResponse response = StorePurchaseResponse.from(result);
        if (result.replay()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(201).body(response);
    }
}
