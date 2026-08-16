package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.gacha.GachaDrawResult;
import com.nayon.api.gacha.GachaHistoryPage;
import com.nayon.api.gacha.GachaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/gacha/draws")
public class GachaController {
    private final CurrentAccountResolver accountResolver;
    private final GachaService gachaService;

    public GachaController(
            CurrentAccountResolver accountResolver,
            GachaService gachaService) {
        this.accountResolver = accountResolver;
        this.gachaService = gachaService;
    }

    @PostMapping
    public ResponseEntity<GachaDrawResponse> draw(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @Valid @RequestBody GachaDrawRequest request) {
        PlayerAccount account = accountResolver.resolve(jwt);
        GachaDrawResult result = gachaService.draw(
                account.id(), requestId, request.toCommand());
        GachaDrawResponse response = GachaDrawResponse.from(result);
        if (result.replay()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(
                URI.create("/api/v1/gacha/draws/" + result.drawId())).body(response);
    }

    @GetMapping
    public GachaHistoryResponse history(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        PlayerAccount account = accountResolver.resolve(jwt);
        GachaHistoryPage page = gachaService.history(account.id(), before, limit);
        return GachaHistoryResponse.from(page);
    }
}
