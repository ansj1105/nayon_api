package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.levelreward.LevelRewardClaimResult;
import com.nayon.api.levelreward.LevelRewardService;
import com.nayon.api.levelreward.LevelRewardTrackCode;
import com.nayon.api.time.ServerClock;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/level-rewards")
public class LevelRewardController {

    private final CurrentAccountResolver accountResolver;
    private final LevelRewardService service;
    private final ServerClock clock;

    public LevelRewardController(
            CurrentAccountResolver accountResolver,
            LevelRewardService service,
            ServerClock clock) {
        this.accountResolver = accountResolver;
        this.service = service;
        this.clock = clock;
    }

    @GetMapping
    public LevelRewardResponse.ListResponse get(
            @AuthenticationPrincipal Jwt jwt) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return LevelRewardResponse.ListResponse.from(
                service.get(account.id()), clock.now());
    }

    @PostMapping("/{trackCode}/{requiredLevel}/claim")
    public ResponseEntity<LevelRewardResponse.ClaimResponse> claim(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @PathVariable LevelRewardTrackCode trackCode,
            @PathVariable int requiredLevel) {
        PlayerAccount account = accountResolver.resolve(jwt);
        LevelRewardClaimResult result = service.claim(
                account.id(), requestId, trackCode, requiredLevel);
        LevelRewardResponse.ClaimResponse response =
                LevelRewardResponse.ClaimResponse.from(result);
        return result.replay()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(201).body(response);
    }
}
