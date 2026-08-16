package com.nayon.api.battle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcBattleRepository implements BattleRepository {
    private final JdbcTemplate jdbc;
    private final EconomyRepository economyRepository;
    private final ObjectMapper objectMapper;

    public JdbcBattleRepository(
            JdbcTemplate jdbc,
            EconomyRepository economyRepository,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.economyRepository = economyRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public BattleSessionResult start(
            UUID accountId,
            UUID requestId,
            String requestHash,
            BattleStartCommand command,
            BattleStageLimit stage,
            BattleStageCatalog.Configuration configuration,
            Instant now) {
        lock("battle-account:" + accountId);
        lock("battle-start:" + accountId + ':' + requestId);
        List<StoredSession> existing = jdbc.query("""
                select request_hash, response_payload from battle_sessions
                 where account_id = ? and request_id = ?
                """, (rs, rowNumber) -> new StoredSession(
                rs.getString("request_hash"),
                readSession(rs.getString("response_payload"))),
                accountId, requestId);
        if (!existing.isEmpty()) {
            StoredSession stored = existing.getFirst();
            if (!stored.requestHash().equals(requestHash)) {
                throw new BattleConflictException();
            }
            return stored.result().asReplay();
        }
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from economy_bootstraps where account_id = ?)
                """, Boolean.class, accountId))) {
            throw new BattleEconomyNotBootstrappedException();
        }

        UUID battleId = UUID.randomUUID();
        Instant expiresAt = now.plusSeconds(configuration.sessionTtlSeconds());
        BattleSessionResult result = new BattleSessionResult(
                battleId, command.stageCode(), configuration.version(),
                now, expiresAt, false);
        jdbc.update("""
                insert into battle_sessions(
                    id, account_id, request_id, request_hash, stage_code,
                    stage_snapshot, client_build, status, response_payload,
                    started_at, expires_at)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, 'ACTIVE', ?::jsonb, ?, ?)
                """, battleId, accountId, requestId, requestHash,
                command.stageCode(), write(stage), command.clientBuild(),
                write(result), java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(expiresAt));
        jdbc.update("""
                insert into player_progression(account_id)
                values (?) on conflict do nothing
                """, accountId);
        return result;
    }

    @Override
    public BattleCompletionResult complete(
            UUID accountId,
            UUID battleId,
            UUID requestId,
            String requestHash,
            BattleCompletionCommand command,
            BattleAnomalyEvaluator evaluator,
            BattleStageCatalog.Configuration configuration,
            Instant now) {
        lock("battle-account:" + accountId);
        lock("battle-complete:" + battleId);
        List<StoredCompletion> completed = jdbc.query("""
                select request_id, request_hash, response_payload
                  from battle_completions
                 where battle_id = ? and account_id = ?
                """, (rs, rowNumber) -> new StoredCompletion(
                rs.getObject("request_id", UUID.class),
                rs.getString("request_hash"),
                readCompletion(rs.getString("response_payload"))),
                battleId, accountId);
        if (!completed.isEmpty()) {
            StoredCompletion stored = completed.getFirst();
            if (!stored.requestId().equals(requestId)
                    || !stored.requestHash().equals(requestHash)) {
                throw new BattleConflictException();
            }
            return stored.result().asReplay();
        }

        List<BattleSessionRow> sessions = jdbc.query("""
                select stage_code, stage_snapshot, status, started_at, expires_at
                  from battle_sessions
                 where id = ? and account_id = ? for update
                """, this::mapSession, battleId, accountId);
        if (sessions.isEmpty()) {
            throw new BattleNotFoundException();
        }
        BattleSessionRow session = sessions.getFirst();
        if (!session.status().equals("ACTIVE")) {
            throw new BattleConflictException();
        }
        BattleEvaluation evaluation = evaluator.evaluate(
                session.startedAt(), session.expiresAt(), now,
                session.stage(), configuration, command);
        BattleRewardState rewardState = !evaluation.anomalies().isEmpty()
                ? BattleRewardState.HELD
                : command.outcome() == BattleOutcome.CLEAR
                        ? BattleRewardState.GRANTED : BattleRewardState.REJECTED;
        long gold = command.outcome() == BattleOutcome.CLEAR
                ? session.stage().clearGold() : 0;
        long exp = command.outcome() == BattleOutcome.CLEAR
                ? session.stage().clearExp() : 0;

        UUID completionId = UUID.randomUUID();
        jdbc.update("""
                insert into battle_completions(
                    id, battle_id, account_id, request_id, request_hash,
                    outcome, elapsed_seconds, kill_count, total_damage,
                    reached_wave, client_ended_at, metrics_payload,
                    response_payload, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                        '{}'::jsonb, ?)
                """, completionId, battleId, accountId, requestId, requestHash,
                command.outcome().name(), command.elapsedSeconds(),
                command.killCount(), command.totalDamage(), command.reachedWave(),
                java.sql.Timestamp.from(command.clientEndedAt()), write(command),
                java.sql.Timestamp.from(now));
        for (BattleAnomaly anomaly : evaluation.anomalies()) {
            jdbc.update("""
                    insert into battle_anomalies(
                        id, battle_id, rule_code, severity,
                        observed_value, expected_value, details)
                    values (?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, UUID.randomUUID(), battleId, anomaly.ruleCode(),
                    anomaly.severity(), anomaly.observedValue(),
                    anomaly.expectedValue(), write(evaluation.details()));
        }
        jdbc.update("""
                insert into battle_rewards(
                    id, battle_id, account_id, state, gold, account_exp,
                    decision_details, decided_at, granted_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, UUID.randomUUID(), battleId, accountId, rewardState.name(),
                gold, exp, write(evaluation.details()), java.sql.Timestamp.from(now),
                rewardState == BattleRewardState.GRANTED
                        ? java.sql.Timestamp.from(now) : null);

        if (rewardState == BattleRewardState.GRANTED) {
            grantGold(accountId, requestId, battleId, gold);
            jdbc.update("""
                    update player_progression
                       set account_exp = account_exp + ?,
                           highest_stage_unlocked = greatest(
                               highest_stage_unlocked, ?),
                           version = version + 1, updated_at = now()
                     where account_id = ?
                    """, exp, session.stage().stageIndex() + 1, accountId);
        }
        long totalExp = jdbc.queryForObject("""
                select account_exp from player_progression where account_id = ?
                """, Long.class, accountId);
        jdbc.update("""
                update battle_sessions
                   set status = 'COMPLETED', completed_at = ? where id = ?
                """, java.sql.Timestamp.from(now), battleId);
        EconomySnapshot economy = economyRepository.findSnapshot(accountId);
        BattleCompletionResult result = new BattleCompletionResult(
                battleId, session.stageCode(), command.outcome(), rewardState,
                gold, exp, totalExp,
                evaluation.anomalies().stream().map(BattleAnomaly::ruleCode).toList(),
                economy, now, false);
        jdbc.update("""
                update battle_completions set response_payload = ?::jsonb where id = ?
                """, write(result), completionId);
        return result;
    }

    @Override
    public BattleHistoryPage history(UUID accountId, UUID before, int limit) {
        List<BattleCompletionResult> rows;
        if (before == null) {
            rows = jdbc.query("""
                    select c.response_payload
                      from battle_completions c
                      join battle_sessions s on s.id = c.battle_id
                     where c.account_id = ?
                     order by s.completed_at desc, s.id desc limit ?
                    """, (rs, rowNumber) -> readCompletion(rs.getString(1)),
                    accountId, limit + 1);
        } else {
            rows = jdbc.query("""
                    select c.response_payload
                      from battle_completions c
                      join battle_sessions s on s.id = c.battle_id
                     where c.account_id = ?
                       and (s.completed_at, s.id) < (
                           select completed_at, id from battle_sessions
                            where id = ? and account_id = ?)
                     order by s.completed_at desc, s.id desc limit ?
                    """, (rs, rowNumber) -> readCompletion(rs.getString(1)),
                    accountId, before, accountId, limit + 1);
        }
        boolean hasMore = rows.size() > limit;
        List<BattleCompletionResult> page = hasMore
                ? new ArrayList<>(rows.subList(0, limit)) : rows;
        UUID next = hasMore ? page.getLast().battleId() : null;
        return new BattleHistoryPage(page, next);
    }

    private void grantGold(
            UUID accountId, UUID requestId, UUID battleId, long gold) {
        jdbc.update("""
                insert into player_wallets(account_id, currency_code, balance)
                values (?, 'GOLD', 0) on conflict do nothing
                """, accountId);
        long before = jdbc.queryForObject("""
                select balance from player_wallets
                 where account_id = ? and currency_code = 'GOLD' for update
                """, Long.class, accountId);
        long after = Math.addExact(before, gold);
        jdbc.update("""
                update player_wallets set balance = ?, version = version + 1,
                       updated_at = now()
                 where account_id = ? and currency_code = 'GOLD'
                """, after, accountId);
        if (gold > 0) {
            jdbc.update("""
                    insert into economy_ledger(
                        id, account_id, asset_type, asset_code, delta,
                        balance_before, balance_after, reason_code,
                        reference_type, reference_id, request_id)
                    values (?, ?, 'CURRENCY', 'GOLD', ?, ?, ?,
                            'BATTLE_REWARD', 'BATTLE', ?, ?)
                    """, UUID.randomUUID(), accountId, gold, before, after,
                    battleId, requestId);
        }
    }

    private BattleSessionRow mapSession(ResultSet rs, int rowNumber) throws SQLException {
        return new BattleSessionRow(
                rs.getString("stage_code"),
                read(rs.getString("stage_snapshot"), BattleStageLimit.class),
                rs.getString("status"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant());
    }

    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> null, key);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Battle data cannot be serialized", exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored battle data is invalid", exception);
        }
    }

    private BattleSessionResult readSession(String json) {
        return read(json, BattleSessionResult.class);
    }

    private BattleCompletionResult readCompletion(String json) {
        return read(json, BattleCompletionResult.class);
    }

    private record StoredSession(String requestHash, BattleSessionResult result) {
    }
    private record StoredCompletion(
            UUID requestId, String requestHash, BattleCompletionResult result) {
    }
    private record BattleSessionRow(
            String stageCode,
            BattleStageLimit stage,
            String status,
            Instant startedAt,
            Instant expiresAt) {
    }
}
