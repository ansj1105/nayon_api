package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;
import com.nayon.api.store.StoreCatalogService;
import com.nayon.api.store.StorePlatform;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/store/catalog")
public class StoreCatalogController {

    private final CurrentAccountResolver accountResolver;
    private final StoreCatalogService service;

    public StoreCatalogController(
            CurrentAccountResolver accountResolver,
            StoreCatalogService service) {
        this.accountResolver = accountResolver;
        this.service = service;
    }

    @GetMapping
    public StoreCatalogResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam StorePlatform platform) {
        PlayerAccount account = accountResolver.resolve(jwt);
        return StoreCatalogResponse.from(service.get(account.id(), platform));
    }
}
