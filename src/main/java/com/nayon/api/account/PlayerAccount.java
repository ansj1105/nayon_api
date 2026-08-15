package com.nayon.api.account;

import java.time.Instant;
import java.util.UUID;

public record PlayerAccount(
        UUID id,
        String publicId,
        AccountStatus status,
        String nickname,
        String avatarCode,
        String frameCode,
        String locale,
        Instant createdAt) {
}
