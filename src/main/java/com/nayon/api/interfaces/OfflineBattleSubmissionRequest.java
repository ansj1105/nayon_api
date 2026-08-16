package com.nayon.api.interfaces;

import com.nayon.api.battle.offline.OfflineBattleSubmissionCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record OfflineBattleSubmissionRequest(
        @NotNull UUID windowId,
        @NotNull @Size(min = 1, max = 20)
        List<@Valid OfflineBattleRunRequest> runs) {
    OfflineBattleSubmissionCommand toCommand() {
        return new OfflineBattleSubmissionCommand(
                windowId, runs.stream().map(OfflineBattleRunRequest::toCommand).toList());
    }
}
