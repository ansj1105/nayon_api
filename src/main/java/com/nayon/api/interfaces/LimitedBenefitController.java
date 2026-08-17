package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.limitedbenefit.LimitedBenefitService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/limited-benefits")
public class LimitedBenefitController {
    private final CurrentAccountResolver accountResolver;
    private final LimitedBenefitService service;

    public LimitedBenefitController(
            CurrentAccountResolver accountResolver,
            LimitedBenefitService service) {
        this.accountResolver = accountResolver;
        this.service = service;
    }

    @GetMapping("/current")
    public ResponseEntity<LimitedBenefitCampaignResponse> current(
            @AuthenticationPrincipal Jwt jwt) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return service.current(account.id())
                .map(LimitedBenefitCampaignResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/offers/{offerCode}/claims")
    public LimitedBenefitClaimResponse claim(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String offerCode,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @RequestBody(required = false) LimitedBenefitClaimRequest request) {
        PlayerAccount account = accountResolver.resolve(jwt);
        if (request != null && (request.receiptId() != null || request.adSessionId() != null)) {
            throw new IllegalArgumentException("Provider proof is not supported by this stage");
        }
        return LimitedBenefitClaimResponse.from(
                service.claimFree(account.id(), requestId, offerCode));
    }
}
