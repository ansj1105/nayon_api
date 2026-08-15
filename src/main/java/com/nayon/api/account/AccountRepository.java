package com.nayon.api.account;

import com.nayon.api.auth.AuthenticatedIdentity;

import java.util.UUID;

public interface AccountRepository {

    PlayerAccount resolveOrCreate(
            AuthenticatedIdentity identity,
            PlayerAccount proposedAccount);

    PlayerAccount updateProfile(UUID accountId, PlayerProfile profile);
}
