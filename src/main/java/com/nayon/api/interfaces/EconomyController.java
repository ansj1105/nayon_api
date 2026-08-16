package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.economy.EconomyBootstrapResult;
import com.nayon.api.economy.EconomyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/economy")
public class EconomyController {

    private final CurrentAccountResolver accountResolver;
    private final EconomyService economyService;

    public EconomyController(
            CurrentAccountResolver accountResolver,
            EconomyService economyService) {
        this.accountResolver = accountResolver;
        this.economyService = economyService;
    }

    @GetMapping
    public EconomyResponse get(@AuthenticationPrincipal Jwt jwt) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return EconomyResponse.from(economyService.get(account.id()));
    }

    @PostMapping("/bootstrap")
    public ResponseEntity<EconomyResponse> bootstrap(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @Valid @RequestBody EconomyBootstrapRequest request) {
        PlayerAccount account = accountResolver.resolve(jwt);
        EconomyBootstrapResult result = economyService.bootstrap(
                account.id(), requestId, request.toCommand());
        EconomyResponse response = EconomyResponse.from(result.snapshot());
        if (result.replay()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(URI.create("/api/v1/economy")).body(response);
    }
}
