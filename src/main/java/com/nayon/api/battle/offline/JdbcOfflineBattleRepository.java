package com.nayon.api.battle.offline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.battle.BattleOutcome;
import com.nayon.api.battle.BattleRewardState;
import com.nayon.api.battle.BattleStageCatalog;
import com.nayon.api.battle.BattleStageLimit;
import com.nayon.api.battle.BattleEconomyNotBootstrappedException;
import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcOfflineBattleRepository implements OfflineBattleRepository {
    private final JdbcTemplate jdbc;
    private final EconomyRepository economyRepository;
    private final ObjectMapper objectMapper;

    public JdbcOfflineBattleRepository(
            JdbcTemplate jdbc,
            EconomyRepository economyRepository,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.economyRepository = economyRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public OfflineBattleWindowResult sync(
            UUID accountId,
            UUID requestId,
            String requestHash,
            Instant now,
            Instant expiresAt,
            BattleStageCatalog.Configuration rules) {
        lock("battle-account:" + accountId);
        List<StoredWindow> existing = jdbc.query("""
                select request_hash, response_payload
                  from offline_play_window_requests
                 where account_id = ? and request_id = ?
                """, (rs, row) -> new StoredWindow(
                rs.getString("request_hash"),
                read(rs.getString("response_payload"), OfflineBattleWindowResult.class)),
                accountId, requestId);
        if (!existing.isEmpty()) {
            StoredWindow stored = existing.getFirst();
            if (!stored.requestHash().equals(requestHash)) {
                throw new OfflineBattleConflictException("Idempotency key was reused");
            }
            List<OfflineBattleWindowResult> active = jdbc.query("""
                    select window_id, opened_at, expires_at
                      from offline_play_budgets where account_id = ?
                    """, (rs, row) -> new OfflineBattleWindowResult(
                    rs.getObject("window_id", UUID.class),
                    rs.getTimestamp("opened_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(), true), accountId);
            if (!active.isEmpty()
                    && !active.getFirst().windowId().equals(stored.result().windowId())) {
                return active.getFirst();
            }
            return stored.result().asReplay();
        }
        requireBootstrapped(accountId);

        UUID windowId = UUID.randomUUID();
        OfflineBattleWindowResult result = new OfflineBattleWindowResult(
                windowId, now, expiresAt, false);
        jdbc.update("""
                insert into offline_play_budgets(
                    account_id, window_id, opened_at, expires_at,
                    consumed_seconds, rules_version, rules_snapshot,
                    version, updated_at)
                values (?, ?, ?, ?, 0, ?, ?::jsonb, 0, ?)
                on conflict (account_id) do update
                   set window_id = excluded.window_id,
                       opened_at = excluded.opened_at,
                       expires_at = excluded.expires_at,
                       consumed_seconds = 0,
                       rules_version = excluded.rules_version,
                       rules_snapshot = excluded.rules_snapshot,
                       version = offline_play_budgets.version + 1,
                       updated_at = excluded.updated_at
                """, accountId, windowId, timestamp(now), timestamp(expiresAt),
                rules.version(), write(rules), timestamp(now));
        jdbc.update("""
                insert into offline_play_window_requests(
                    id, account_id, request_id, request_hash,
                    window_id, response_payload, created_at)
                values (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), accountId, requestId, requestHash,
                windowId, write(result), timestamp(now));
        return result;
    }

    @Override
    public OfflineBattleSubmissionResult submit(
            UUID accountId,
            UUID requestId,
            String requestHash,
            OfflineBattleSubmissionCommand command,
            OfflineBattleEvaluator evaluator,
            Instant now) {
        lock("battle-account:" + accountId);
        List<StoredSubmission> existing = jdbc.query("""
                select request_hash, response_payload
                  from offline_battle_submissions
                 where account_id = ? and request_id = ?
                """, (rs, row) -> new StoredSubmission(
                rs.getString("request_hash"), read(
                        rs.getString("response_payload"),
                        OfflineBattleSubmissionResult.class)),
                accountId, requestId);
        if (!existing.isEmpty()) {
            StoredSubmission stored = existing.getFirst();
            if (!stored.requestHash().equals(requestHash)) {
                throw new OfflineBattleConflictException("Idempotency key was reused");
            }
            return stored.result().asReplay();
        }
        requireBootstrapped(accountId);

        List<OfflinePlayBudget> budgets = jdbc.query("""
                select window_id, opened_at, expires_at, consumed_seconds,
                       rules_snapshot
                  from offline_play_budgets
                 where account_id = ? for update
                """, (rs, row) -> new OfflinePlayBudget(
                rs.getObject("window_id", UUID.class),
                rs.getTimestamp("opened_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getLong("consumed_seconds"),
                read(rs.getString("rules_snapshot"),
                        BattleStageCatalog.Configuration.class)), accountId);
        if (budgets.isEmpty() || !budgets.getFirst().windowId().equals(command.windowId())) {
            throw new OfflineBattleConflictException("Offline play window is invalid");
        }
        OfflinePlayBudget budget = budgets.getFirst();
        List<String> anomalies = new ArrayList<>(
                evaluator.evaluate(budget, command.runs(), now));
        jdbc.update("insert into player_progression(account_id) values (?) on conflict do nothing",
                accountId);
        int unlockedStage = jdbc.queryForObject("""
                select highest_stage_unlocked from player_progression
                 where account_id = ?
                """, Integer.class, accountId);
        for (OfflineBattleRunCommand run : command.runs()) {
            BattleStageLimit stage = evaluator.require(
                    budget.rules(), run.stageCode());
            if (stage.stageIndex() > unlockedStage
                    && !anomalies.contains("STAGE_NOT_UNLOCKED")) {
                anomalies.add("STAGE_NOT_UNLOCKED");
            }
            if (stage.stageIndex() <= unlockedStage
                    && run.outcome() == BattleOutcome.CLEAR) {
                unlockedStage = Math.max(unlockedStage, stage.stageIndex() + 1);
            }
            Boolean duplicate = jdbc.queryForObject("""
                    select exists(select 1 from offline_battle_runs
                                   where account_id = ? and run_id = ?)
                    """, Boolean.class, accountId, run.runId());
            if (Boolean.TRUE.equals(duplicate)) {
                throw new OfflineBattleConflictException(
                        "Offline run was already submitted");
            }
        }

        long requestedSeconds = command.runs().stream()
                .mapToLong(OfflineBattleRunCommand::elapsedSeconds).sum();
        boolean hasClear = command.runs().stream()
                .anyMatch(run -> run.outcome() == BattleOutcome.CLEAR);
        BattleRewardState state = !anomalies.isEmpty() ? BattleRewardState.HELD
                : hasClear ? BattleRewardState.GRANTED : BattleRewardState.REJECTED;
        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                insert into offline_battle_submissions(
                    id, account_id, request_id, request_hash, window_id,
                    requested_seconds, reward_state, response_payload, created_at)
                values (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?)
                """, submissionId, accountId, requestId, requestHash,
                command.windowId(), requestedSeconds, state.name(), timestamp(now));

        long totalGold = 0;
        long totalExp = 0;
        long totalScroll = 0;
        long totalCoupon = 0;
        int highestUnlocked = 1;
        for (OfflineBattleRunCommand run : command.runs()) {
            BattleStageLimit stage = evaluator.require(
                    budget.rules(), run.stageCode());
            boolean clear = run.outcome() == BattleOutcome.CLEAR;
            long gold = clear ? stage.clearGold() : 0;
            long exp = clear ? 120L + stage.stageIndex() * 45L
                    + Math.max(1L, Math.min(20L,
                            (run.elapsedSeconds() + 59L) / 60L)) * 18L
                    + Math.max(0L, Math.min(180L, run.killCount() / 25L)) : 0;
            long scroll = clear ? 10 : 0;
            long coupon = clear ? stage.stageIndex() : 0;
            UUID storedRunId = UUID.randomUUID();
            jdbc.update("""
                    insert into offline_battle_runs(
                        id, submission_id, account_id, run_id, stage_code,
                        outcome, elapsed_seconds, kill_count, total_damage,
                        reached_wave, metrics_payload, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, storedRunId, submissionId, accountId, run.runId(),
                    run.stageCode(), run.outcome().name(), run.elapsedSeconds(),
                    run.killCount(), run.totalDamage(), run.reachedWave(),
                    write(run), timestamp(now));
            jdbc.update("""
                    insert into offline_battle_decisions(
                        id, run_id, state, gold, account_exp,
                        random_scroll, level_up_coupon, anomaly_reasons,
                        decided_at, granted_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    """, UUID.randomUUID(), storedRunId, state.name(), gold, exp,
                    scroll, coupon, write(anomalies), timestamp(now),
                    state == BattleRewardState.GRANTED ? timestamp(now) : null);
            if (state == BattleRewardState.GRANTED) {
                totalGold = Math.addExact(totalGold, gold);
                totalExp = Math.addExact(totalExp, exp);
                totalScroll = Math.addExact(totalScroll, scroll);
                totalCoupon = Math.addExact(totalCoupon, coupon);
                if (clear) highestUnlocked = Math.max(highestUnlocked, stage.stageIndex() + 1);
            }
        }

        if (state == BattleRewardState.GRANTED) {
            grantCurrency(accountId, requestId, submissionId, "GOLD", totalGold);
            grantItem(accountId, requestId, submissionId, "RANDOM_SCROLL", totalScroll);
            grantItem(accountId, requestId, submissionId, "LEVEL_UP_COUPON", totalCoupon);
            jdbc.update("""
                    update player_progression
                       set account_exp = account_exp + ?,
                           highest_stage_unlocked = greatest(highest_stage_unlocked, ?),
                           version = version + 1, updated_at = ?
                     where account_id = ?
                    """, totalExp, highestUnlocked, timestamp(now), accountId);
        }
        jdbc.update("""
                update offline_play_budgets
                   set consumed_seconds = consumed_seconds + ?,
                       version = version + 1, updated_at = ?
                 where account_id = ?
                """, requestedSeconds, timestamp(now), accountId);

        EconomySnapshot economy = economyRepository.findSnapshot(accountId);
        OfflineBattleSubmissionResult result = new OfflineBattleSubmissionResult(
                submissionId, state,
                command.runs().stream().map(OfflineBattleRunCommand::runId).toList(),
                anomalies, totalExp, economy, false);
        jdbc.update("update offline_battle_submissions set response_payload = ?::jsonb where id = ?",
                write(result), submissionId);
        return result;
    }

    private void grantCurrency(
            UUID accountId, UUID requestId, UUID referenceId,
            String code, long quantity) {
        jdbc.update("insert into player_wallets(account_id, currency_code, balance) "
                + "values (?, ?, 0) on conflict do nothing", accountId, code);
        long before = jdbc.queryForObject("select balance from player_wallets "
                + "where account_id = ? and currency_code = ? for update",
                Long.class, accountId, code);
        long after = Math.addExact(before, quantity);
        jdbc.update("update player_wallets set balance = ?, version = version + 1, "
                + "updated_at = now() where account_id = ? and currency_code = ?",
                after, accountId, code);
        ledger(accountId, requestId, referenceId, "CURRENCY", code,
                quantity, before, after);
    }

    private void grantItem(
            UUID accountId, UUID requestId, UUID referenceId,
            String code, long quantity) {
        jdbc.update("insert into player_items(account_id, item_code, quantity) "
                + "values (?, ?, 0) on conflict do nothing", accountId, code);
        long before = jdbc.queryForObject("select quantity from player_items "
                + "where account_id = ? and item_code = ? for update",
                Long.class, accountId, code);
        long after = Math.addExact(before, quantity);
        jdbc.update("update player_items set quantity = ?, version = version + 1, "
                + "updated_at = now() where account_id = ? and item_code = ?",
                after, accountId, code);
        ledger(accountId, requestId, referenceId, "ITEM", code,
                quantity, before, after);
    }

    private void ledger(
            UUID accountId, UUID requestId, UUID referenceId,
            String assetType, String assetCode,
            long delta, long before, long after) {
        if (delta == 0) return;
        jdbc.update("""
                insert into economy_ledger(
                    id, account_id, asset_type, asset_code, delta,
                    balance_before, balance_after, reason_code,
                    reference_type, reference_id, request_id)
                values (?, ?, ?, ?, ?, ?, ?, 'OFFLINE_BATTLE_REWARD',
                        'OFFLINE_BATTLE', ?, ?)
                """, UUID.randomUUID(), accountId, assetType, assetCode,
                delta, before, after, referenceId, requestId);
    }

    private void requireBootstrapped(UUID accountId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from economy_bootstraps where account_id = ?)
                """, Boolean.class, accountId))) {
            throw new BattleEconomyNotBootstrappedException();
        }
    }

    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> null, key);
    }

    private java.sql.Timestamp timestamp(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Offline battle data cannot be serialized", exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored offline battle data is invalid", exception);
        }
    }

    private record StoredWindow(String requestHash, OfflineBattleWindowResult result) {}
    private record StoredSubmission(
            String requestHash, OfflineBattleSubmissionResult result) {}
}
