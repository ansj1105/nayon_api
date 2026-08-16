package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.battle.BattleCompletionResult;
import com.nayon.api.battle.BattleHistoryPage;
import com.nayon.api.battle.BattleService;
import com.nayon.api.battle.BattleSessionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/battles")
public class BattleController {
    private final CurrentAccountResolver accountResolver;
    private final BattleService battleService;

    public BattleController(
            CurrentAccountResolver accountResolver,
            BattleService battleService) {
        this.accountResolver = accountResolver;
        this.battleService = battleService;
    }

    @PostMapping
    public ResponseEntity<BattleSessionResponse> start(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @Valid @RequestBody BattleStartRequest request) {
        PlayerAccount account = accountResolver.resolve(jwt);
        BattleSessionResult result = battleService.start(
                account.id(), requestId, request.toCommand());
        BattleSessionResponse response = BattleSessionResponse.from(result);
        if (result.replay()) return ResponseEntity.ok(response);
        return ResponseEntity.created(
                URI.create("/api/v1/battles/" + result.battleId())).body(response);
    }

    @PostMapping("/{battleId}/complete")
    public ResponseEntity<BattleCompletionResponse> complete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID battleId,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @Valid @RequestBody BattleCompleteRequest request) {
        PlayerAccount account = accountResolver.resolve(jwt);
        BattleCompletionResult result = battleService.complete(
                account.id(), battleId, requestId, request.toCommand());
        BattleCompletionResponse response = BattleCompletionResponse.from(result);
        return result.replay()
                ? ResponseEntity.ok(response)
                : ResponseEntity.created(
                        URI.create("/api/v1/battles/" + battleId)).body(response);
    }

    @GetMapping
    public BattleHistoryResponse history(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        PlayerAccount account = accountResolver.resolve(jwt);
        BattleHistoryPage page = battleService.history(account.id(), before, limit);
        return BattleHistoryResponse.from(page);
    }
}
