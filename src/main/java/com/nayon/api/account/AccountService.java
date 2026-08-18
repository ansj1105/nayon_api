package com.nayon.api.account;

import com.nayon.api.auth.AuthenticatedIdentity;
import com.nayon.api.time.ServerClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class AccountService {

    private final AccountRepository repository;
    private final Supplier<UUID> idSupplier;
    private final ServerClock clock;

    @Autowired
    public AccountService(AccountRepository repository, ServerClock clock) {
        this(repository, UUID::randomUUID, clock);
    }

    AccountService(AccountRepository repository, Supplier<UUID> idSupplier) {
        this(repository, idSupplier, new ServerClock());
    }

    AccountService(
            AccountRepository repository,
            Supplier<UUID> idSupplier,
            ServerClock clock) {
        this.repository = repository;
        this.idSupplier = idSupplier;
        this.clock = clock;
    }

    @Transactional
    public PlayerAccount resolveOrCreate(AuthenticatedIdentity identity) {
        UUID id = idSupplier.get();
        String shortId = id.toString().replace("-", "")
                .substring(0, 8).toUpperCase(Locale.ROOT);
        PlayerAccount proposed = new PlayerAccount(
                id,
                "NYAON-" + shortId,
                AccountStatus.ACTIVE,
                "Hunter-" + shortId,
                null,
                null,
                null,
                clock.now());
        return repository.resolveOrCreate(identity, proposed);
    }

    @Transactional
    public PlayerAccount updateProfile(PlayerAccount current, PlayerProfile requested) {
        PlayerProfile merged = new PlayerProfile(
                requested.nickname() == null ? current.nickname() : requested.nickname(),
                requested.avatarCode() == null ? current.avatarCode() : requested.avatarCode(),
                requested.frameCode() == null ? current.frameCode() : requested.frameCode());
        return repository.updateProfile(current.id(), merged);
    }
}
