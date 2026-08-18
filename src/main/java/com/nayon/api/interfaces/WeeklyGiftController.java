package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.weeklygift.WeeklyGiftService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/weekly-gift")
public class WeeklyGiftController {
    private final CurrentAccountResolver accountResolver;
    private final WeeklyGiftService service;

    public WeeklyGiftController(
            CurrentAccountResolver accountResolver,
            WeeklyGiftService service) {
        this.accountResolver = accountResolver;
        this.service = service;
    }

    @GetMapping
    public WeeklyGiftResponse get(@AuthenticationPrincipal Jwt jwt) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return WeeklyGiftResponse.from(service.get(account.id()));
    }

    @PostMapping("/check-in")
    public WeeklyGiftResponse checkIn(@AuthenticationPrincipal Jwt jwt) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return WeeklyGiftResponse.from(service.checkIn(account.id()));
    }

    @PostMapping("/claim")
    public WeeklyGiftResponse claim(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestId) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return WeeklyGiftResponse.from(service.claim(account.id(), requestId));
    }
}
