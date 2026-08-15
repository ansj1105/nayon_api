package com.nayon.api.account;

import com.nayon.api.auth.AuthProvider;
import com.nayon.api.auth.AuthenticatedIdentity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AccountServiceTest {

    @Test
    void sameProviderAndSubjectResolveToSameAccount() {
        AccountService service = service();
        AuthenticatedIdentity identity =
                new AuthenticatedIdentity(AuthProvider.GOOGLE, "subject-a");

        PlayerAccount first = service.resolveOrCreate(identity);
        PlayerAccount second = service.resolveOrCreate(identity);

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void sameSubjectAtDifferentProvidersResolvesToDifferentAccounts() {
        AccountService service = service();

        PlayerAccount google = service.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.GOOGLE, "subject-a"));
        PlayerAccount apple = service.resolveOrCreate(
                new AuthenticatedIdentity(AuthProvider.APPLE, "subject-a"));

        assertThat(apple.id()).isNotEqualTo(google.id());
    }

    private AccountService service() {
        AtomicInteger sequence = new AtomicInteger();
        return new AccountService(new InMemoryAccountRepository(), () ->
                new UUID(0, sequence.incrementAndGet()));
    }

    private static final class InMemoryAccountRepository implements AccountRepository {
        private final Map<AuthenticatedIdentity, PlayerAccount> accounts = new HashMap<>();

        @Override
        public PlayerAccount resolveOrCreate(
                AuthenticatedIdentity identity,
                PlayerAccount proposedAccount) {
            return accounts.computeIfAbsent(identity, ignored -> proposedAccount);
        }

        @Override
        public PlayerAccount updateProfile(UUID accountId, PlayerProfile profile) {
            throw new UnsupportedOperationException();
        }
    }
}
