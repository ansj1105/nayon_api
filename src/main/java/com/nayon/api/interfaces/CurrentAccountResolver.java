package com.nayon.api.interfaces;

import com.nayon.api.account.AccountService;
import com.nayon.api.account.PlayerAccount;
import com.nayon.api.auth.IdentityExtractor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentAccountResolver {

    private final IdentityExtractor identityExtractor;
    private final AccountService accountService;

    public CurrentAccountResolver(
            IdentityExtractor identityExtractor,
            AccountService accountService) {
        this.identityExtractor = identityExtractor;
        this.accountService = accountService;
    }

    public PlayerAccount resolve(Jwt jwt) {
        return accountService.resolveOrCreate(identityExtractor.extract(jwt));
    }
}
