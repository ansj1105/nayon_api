package com.nayon.api.interfaces;

import com.nayon.api.limitedbenefit.admob.AdMobRewardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/admob")
public class AdMobRewardCallbackController {
    private final AdMobRewardService service;

    public AdMobRewardCallbackController(AdMobRewardService service) {
        this.service = service;
    }

    @GetMapping("/rewarded-callback")
    public ResponseEntity<Void> rewardedCallback(HttpServletRequest request) {
        service.accept(request.getQueryString());
        return ResponseEntity.ok().build();
    }
}
