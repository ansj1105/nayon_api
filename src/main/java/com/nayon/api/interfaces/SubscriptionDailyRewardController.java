package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.subscription.SubscriptionDailyRewardResult;
import com.nayon.api.subscription.SubscriptionDailyRewardService;
import com.nayon.api.subscription.SubscriptionPlanCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/subscriptions")
public class SubscriptionDailyRewardController {

    private final CurrentAccountResolver accountResolver;
    private final SubscriptionDailyRewardService service;

    public SubscriptionDailyRewardController(
            CurrentAccountResolver accountResolver,
            SubscriptionDailyRewardService service) {
        this.accountResolver = accountResolver;
        this.service = service;
    }

    @PostMapping("/{planCode}/daily-reward/claim")
    public ResponseEntity<Response> claim(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @PathVariable SubscriptionPlanCode planCode) {
        PlayerAccount account = accountResolver.resolve(jwt);
        SubscriptionDailyRewardResult result = service.claim(
                account.id(), requestId, planCode);
        Response response = Response.from(result);
        return result.replay()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(201).body(response);
    }

    public record Response(
            UUID grantId,
            String planCode,
            LocalDate rewardDate,
            SubscriptionResponse.Reward reward,
            EconomyResponse economy,
            boolean replay) {

        static Response from(SubscriptionDailyRewardResult result) {
            return new Response(
                    result.grantId(), result.planCode().name(), result.rewardDate(),
                    new SubscriptionResponse.Reward(
                            result.reward().assetCode(), result.reward().amount(),
                            result.reward().balance()),
                    EconomyResponse.from(result.economy()), result.replay());
        }
    }
}
