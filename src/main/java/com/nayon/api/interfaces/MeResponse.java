package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerAccount;

import java.time.Instant;

public record MeResponse(
        String publicId,
        String status,
        String nickname,
        String avatarCode,
        String frameCode,
        String locale,
        Instant createdAt) {

    public static MeResponse from(PlayerAccount account) {
        return new MeResponse(
                account.publicId(),
                account.status().name(),
                account.nickname(),
                account.avatarCode(),
                account.frameCode(),
                account.locale(),
                account.createdAt());
    }
}
