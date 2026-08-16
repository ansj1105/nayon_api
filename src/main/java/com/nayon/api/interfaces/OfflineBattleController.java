package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.battle.offline.OfflineBattleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/offline-battles")
public class OfflineBattleController {
    private final CurrentAccountResolver accountResolver;
    private final OfflineBattleService service;

    public OfflineBattleController(
            CurrentAccountResolver accountResolver,
            OfflineBattleService service) {
        this.accountResolver = accountResolver;
        this.service = service;
    }

    @PostMapping("/sync")
    public ResponseEntity<OfflineBattleWindowResponse> sync(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestId) {
        PlayerAccount account = accountResolver.resolve(jwt);
        var result = service.sync(account.id(), requestId);
        var response = OfflineBattleWindowResponse.from(result);
        return result.replay() ? ResponseEntity.ok(response)
                : ResponseEntity.created(URI.create("/api/v1/offline-battles/sync"))
                        .body(response);
    }

    @PostMapping
    public ResponseEntity<OfflineBattleSubmissionResponse> submit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @Valid @RequestBody OfflineBattleSubmissionRequest request) {
        PlayerAccount account = accountResolver.resolve(jwt);
        var result = service.submit(account.id(), requestId, request.toCommand());
        var response = OfflineBattleSubmissionResponse.from(result);
        return result.replay() ? ResponseEntity.ok(response)
                : ResponseEntity.created(URI.create(
                        "/api/v1/offline-battles/" + result.submissionId()))
                        .body(response);
    }
}
