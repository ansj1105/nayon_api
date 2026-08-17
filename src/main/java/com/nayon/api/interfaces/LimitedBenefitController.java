package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.limitedbenefit.LimitedBenefitService;
import com.nayon.api.limitedbenefit.admob.AdMobRewardService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/limited-benefits")
public class LimitedBenefitController {
    private final CurrentAccountResolver accountResolver;
    private final LimitedBenefitService service;
    private final AdMobRewardService adMobRewardService;

    public LimitedBenefitController(
            CurrentAccountResolver accountResolver,
            LimitedBenefitService service,
            AdMobRewardService adMobRewardService) {
        this.accountResolver = accountResolver;
        this.service = service;
        this.adMobRewardService = adMobRewardService;
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
        return LimitedBenefitClaimResponse.from(
                service.claim(
                        account.id(), requestId, offerCode,
                        request == null ? null : request.receiptId(),
                        request == null ? null : request.adSessionId()));
    }

    @PostMapping("/offers/{offerCode}/ad-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public LimitedBenefitAdSessionResponse createAdSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String offerCode) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return LimitedBenefitAdSessionResponse.from(
                adMobRewardService.createSession(account.id(), offerCode));
    }
}
