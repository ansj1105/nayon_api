package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.store.FirstPurchaseRewardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/store/first-purchase-reward")
public class FirstPurchaseRewardController {
    private final CurrentAccountResolver accountResolver;
    private final FirstPurchaseRewardService service;

    public FirstPurchaseRewardController(
            CurrentAccountResolver accountResolver,
            FirstPurchaseRewardService service) {
        this.accountResolver = accountResolver;
        this.service = service;
    }

    @GetMapping
    public FirstPurchaseRewardResponse get(@AuthenticationPrincipal Jwt jwt) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return service.get(account.id())
                .map(FirstPurchaseRewardResponse::from)
                .orElseGet(FirstPurchaseRewardResponse::notGranted);
    }
}
