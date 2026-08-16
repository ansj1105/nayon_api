package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.accountlink.AccountLinkRewardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/account-link-reward")
public class AccountLinkRewardController {
    private final CurrentAccountResolver accountResolver;
    private final AccountLinkRewardService service;

    public AccountLinkRewardController(
            CurrentAccountResolver accountResolver,
            AccountLinkRewardService service) {
        this.accountResolver = accountResolver;
        this.service = service;
    }

    @GetMapping
    public AccountLinkRewardResponse get(@AuthenticationPrincipal Jwt jwt) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return AccountLinkRewardResponse.from(service.get(account.id()));
    }

    @PostMapping("/claim")
    public AccountLinkRewardResponse claim(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestId) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return AccountLinkRewardResponse.from(service.claim(account.id(), requestId));
    }
}
