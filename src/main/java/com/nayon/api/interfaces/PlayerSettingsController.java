package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.settings.PlayerSettingsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/settings")
public class PlayerSettingsController {

    private final CurrentAccountResolver accountResolver;
    private final PlayerSettingsService service;

    public PlayerSettingsController(
            CurrentAccountResolver accountResolver,
            PlayerSettingsService service) {
        this.accountResolver = accountResolver;
        this.service = service;
    }

    @GetMapping
    public PlayerSettingsResponse get(@AuthenticationPrincipal Jwt jwt) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return PlayerSettingsResponse.from(service.get(account.id()));
    }

    @PatchMapping
    public PlayerSettingsResponse patch(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody PlayerSettingsPatchRequest request) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return PlayerSettingsResponse.from(service.patch(account.id(), request.toPatch()));
    }
}
