package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.korion.KorionWalletLinkService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/wallet-links/korion")
public class KorionWalletLinkController {
    private final CurrentAccountResolver accountResolver;
    private final KorionWalletLinkService service;

    public KorionWalletLinkController(
            CurrentAccountResolver accountResolver,
            KorionWalletLinkService service) {
        this.accountResolver = accountResolver;
        this.service = service;
    }

    @GetMapping
    public KorionWalletLinkResponse get(@AuthenticationPrincipal Jwt jwt) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return KorionWalletLinkResponse.from(service.get(account.id()));
    }

    @PostMapping("/requests")
    public ResponseEntity<KorionWalletLinkResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody KorionWalletLinkCreateRequest request) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return ResponseEntity.accepted()
                .body(KorionWalletLinkResponse.from(service.create(account.id(), request.address())));
    }

    @GetMapping("/requests/{requestId}")
    public KorionWalletLinkResponse reconcile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return KorionWalletLinkResponse.from(service.reconcile(account.id(), requestId));
    }

    @DeleteMapping
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal Jwt jwt) {
        PlayerAccount account = accountResolver.resolve(jwt);
        service.unlink(account.id());
        return ResponseEntity.noContent().build();
    }
}
