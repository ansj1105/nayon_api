package com.nayon.api.interfaces;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final CurrentAccountResolver accountResolver;
    private final AccountService accountService;

    public MeController(
            CurrentAccountResolver accountResolver,
            AccountService accountService) {
        this.accountResolver = accountResolver;
        this.accountService = accountService;
    }

    @GetMapping
    public MeResponse get(@AuthenticationPrincipal Jwt jwt) {
        return MeResponse.from(accountResolver.resolve(jwt));
    }

    @PatchMapping
    public MeResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfileRequest request) {
        PlayerAccount current = accountResolver.resolve(jwt);
        return MeResponse.from(accountService.updateProfile(current, request.profile()));
    }
}
