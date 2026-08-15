package com.nayon.api.interfaces;

import com.nayon.api.account.PlayerProfile;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(min = 1, max = 30) String nickname,
        @Size(max = 80) String avatarCode,
        @Size(max = 80) String frameCode) {

    public PlayerProfile profile() {
        return new PlayerProfile(nickname, avatarCode, frameCode);
    }
}
