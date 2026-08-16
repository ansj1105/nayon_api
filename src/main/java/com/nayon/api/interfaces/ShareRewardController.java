package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.share.ShareRewardService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/share-reward")
public class ShareRewardController {

    private final CurrentAccountResolver accountResolver;
    private final ShareRewardService service;

    public ShareRewardController(
            CurrentAccountResolver accountResolver,
            ShareRewardService service) {
        this.accountResolver = accountResolver;
        this.service = service;
    }

    @GetMapping
    public ShareRewardResponse get(@AuthenticationPrincipal Jwt jwt) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return ShareRewardResponse.from(service.get(account.id()));
    }

    @PostMapping("/share-opened")
    public ShareRewardResponse markOpened(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody(required = false) ShareOpenedRequest request) {
        PlayerAccount account = accountResolver.resolve(jwt);
        String target = request == null ? null : request.target();
        return ShareRewardResponse.from(service.markOpened(account.id(), target));
    }

    @PostMapping("/claim")
    public ShareRewardResponse claim(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestId) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return ShareRewardResponse.from(service.claim(account.id(), requestId));
    }
}
