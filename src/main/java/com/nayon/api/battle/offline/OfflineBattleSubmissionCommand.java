package com.nayon.api.battle.offline;

import java.util.List;
import java.util.UUID;

public record OfflineBattleSubmissionCommand(
        UUID windowId,
        List<OfflineBattleRunCommand> runs) {
    public OfflineBattleSubmissionCommand {
        runs = List.copyOf(runs);
    }
}
