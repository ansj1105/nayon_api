package com.nayon.api.economy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcEconomyRepository implements EconomyRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcEconomyRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public EconomySnapshot findSnapshot(UUID accountId) {
        Map<String, Long> currencies = queryAssets(
                "select currency_code, balance from player_wallets where account_id = ? order by currency_code",
                accountId);
        Map<String, Long> items = queryAssets(
                "select item_code, quantity from player_items where account_id = ? order by item_code",
                accountId);
        List<PlayerEquipment> equipment = jdbc.query("""
                select id, equipment_code, grade, level, locked
                  from player_equipment
                 where account_id = ?
                 order by created_at, id
                """, this::mapEquipment, accountId);
        boolean bootstrapped = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                    select 1 from economy_bootstraps where account_id = ?)
                """, Boolean.class, accountId));
        return new EconomySnapshot(
                accountId, currencies, items, equipment, bootstrapped);
    }

    @Override
    public Optional<EconomyBootstrapRecord> findBootstrapByAccountId(UUID accountId) {
        return queryBootstrap("where account_id = ?", accountId);
    }

    @Override
    public Optional<EconomyBootstrapRecord> findBootstrapByRequestId(UUID requestId) {
        return queryBootstrap("where request_id = ?", requestId);
    }

    @Override
    public EconomyBootstrapResult createBootstrap(
            UUID accountId,
            UUID requestId,
            String requestHash,
            EconomyBootstrapCommand command) {
        lock("economy-account:" + accountId);
        lock("economy-request:" + requestId);

        Optional<EconomyBootstrapRecord> byRequest = findBootstrapByRequestId(requestId);
        Optional<EconomyBootstrapRecord> byAccount = findBootstrapByAccountId(accountId);
        Optional<EconomyBootstrapRecord> existing = byRequest.or(() -> byAccount);
        if (existing.isPresent()) {
            EconomyBootstrapRecord previous = existing.get();
            if (previous.accountId().equals(accountId)
                    && previous.requestId().equals(requestId)
                    && previous.requestHash().equals(requestHash)) {
                return new EconomyBootstrapResult(previous.snapshot(), true);
            }
            throw new EconomyBootstrapConflictException();
        }

        for (Map.Entry<String, Long> currency : command.currencies().entrySet()) {
            insertCurrency(accountId, requestId, currency.getKey(), currency.getValue());
        }
        for (Map.Entry<String, Long> item : command.items().entrySet()) {
            insertItem(accountId, requestId, item.getKey(), item.getValue());
        }
        for (EconomyBootstrapEquipment equipment : command.equipment()) {
            for (int index = 0; index < equipment.quantity(); index++) {
                jdbc.update("""
                        insert into player_equipment(
                            id, account_id, equipment_code, grade,
                            source_type, source_id)
                        values (?, ?, ?, ?, 'BOOTSTRAP', ?)
                        """,
                        UUID.randomUUID(), accountId, equipment.equipmentCode(),
                        equipment.grade(), requestId);
            }
        }

        jdbc.update("""
                insert into economy_bootstraps(
                    account_id, request_id, request_hash, response_payload)
                values (?, ?, ?, '{}'::jsonb)
                """, accountId, requestId, requestHash);
        EconomySnapshot snapshot = findSnapshot(accountId);
        jdbc.update("""
                update economy_bootstraps
                   set response_payload = ?::jsonb
                 where account_id = ?
                """, writeSnapshot(snapshot), accountId);
        return new EconomyBootstrapResult(snapshot, false);
    }

    @Override
    public EconomySnapshot creditCurrency(
            UUID accountId,
            UUID requestId,
            String currencyCode,
            long amount,
            String reasonCode,
            String referenceType,
            UUID referenceId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
        lock("economy-account:" + accountId);
        jdbc.update("""
                insert into player_wallets(account_id, currency_code, balance)
                values (?, ?, 0)
                on conflict (account_id, currency_code) do nothing
                """, accountId, currencyCode);
        long before = jdbc.queryForObject("""
                select balance
                  from player_wallets
                 where account_id = ? and currency_code = ?
                   for update
                """, Long.class, accountId, currencyCode);
        long after = Math.addExact(before, amount);
        jdbc.update("""
                update player_wallets
                   set balance = ?, version = version + 1, updated_at = now()
                 where account_id = ? and currency_code = ?
                """, after, accountId, currencyCode);
        jdbc.update("""
                insert into economy_ledger(
                    id, account_id, asset_type, asset_code, delta,
                    balance_before, balance_after, reason_code,
                    reference_type, reference_id, request_id)
                values (?, ?, 'CURRENCY', ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), accountId, currencyCode, amount,
                before, after, reasonCode, referenceType, referenceId, requestId);
        return findSnapshot(accountId);
    }

    @Override
    public EconomySnapshot creditItem(
            UUID accountId,
            UUID requestId,
            String itemCode,
            long amount,
            String reasonCode,
            String referenceType,
            UUID referenceId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
        lock("economy-account:" + accountId);
        jdbc.update("""
                insert into player_items(account_id, item_code, quantity)
                values (?, ?, 0)
                on conflict (account_id, item_code) do nothing
                """, accountId, itemCode);
        long before = jdbc.queryForObject("""
                select quantity from player_items
                 where account_id = ? and item_code = ? for update
                """, Long.class, accountId, itemCode);
        long after = Math.addExact(before, amount);
        jdbc.update("""
                update player_items
                   set quantity = ?, version = version + 1, updated_at = now()
                 where account_id = ? and item_code = ?
                """, after, accountId, itemCode);
        jdbc.update("""
                insert into economy_ledger(
                    id, account_id, asset_type, asset_code, delta,
                    balance_before, balance_after, reason_code,
                    reference_type, reference_id, request_id)
                values (?, ?, 'ITEM', ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), accountId, itemCode, amount,
                before, after, reasonCode, referenceType, referenceId, requestId);
        return findSnapshot(accountId);
    }

    private Map<String, Long> queryAssets(String sql, UUID accountId) {
        Map<String, Long> values = new LinkedHashMap<>();
        jdbc.query(sql, (RowCallbackHandler) resultSet -> values.put(
                resultSet.getString(1), resultSet.getLong(2)), accountId);
        return values;
    }

    private Optional<EconomyBootstrapRecord> queryBootstrap(
            String predicate,
            UUID value) {
        List<EconomyBootstrapRecord> records = jdbc.query("""
                select account_id, request_id, request_hash,
                       response_payload, created_at
                  from economy_bootstraps
                """ + predicate,
                (rs, rowNumber) -> new EconomyBootstrapRecord(
                        rs.getObject("account_id", UUID.class),
                        rs.getObject("request_id", UUID.class),
                        rs.getString("request_hash"),
                        readSnapshot(rs.getString("response_payload")),
                        rs.getTimestamp("created_at").toInstant()),
                value);
        return records.stream().findFirst();
    }

    private void insertCurrency(
            UUID accountId,
            UUID requestId,
            String code,
            long balance) {
        jdbc.update("""
                insert into player_wallets(account_id, currency_code, balance)
                values (?, ?, ?)
                """, accountId, code, balance);
        insertLedger(accountId, requestId, "CURRENCY", code, balance);
    }

    private void insertItem(
            UUID accountId,
            UUID requestId,
            String code,
            long quantity) {
        jdbc.update("""
                insert into player_items(account_id, item_code, quantity)
                values (?, ?, ?)
                """, accountId, code, quantity);
        insertLedger(accountId, requestId, "ITEM", code, quantity);
    }

    private void insertLedger(
            UUID accountId,
            UUID requestId,
            String assetType,
            String assetCode,
            long value) {
        jdbc.update("""
                insert into economy_ledger(
                    id, account_id, asset_type, asset_code, delta,
                    balance_before, balance_after, reason_code,
                    reference_type, reference_id, request_id)
                values (?, ?, ?, ?, ?, 0, ?, 'BOOTSTRAP',
                        'BOOTSTRAP', ?, ?)
                """,
                UUID.randomUUID(), accountId, assetType, assetCode,
                value, value, requestId, requestId);
    }

    private void lock(String key) {
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> null,
                key);
    }

    private PlayerEquipment mapEquipment(ResultSet rs, int rowNumber) throws SQLException {
        return new PlayerEquipment(
                rs.getObject("id", UUID.class),
                rs.getString("equipment_code"),
                rs.getString("grade"),
                rs.getInt("level"),
                rs.getBoolean("locked"));
    }

    private EconomySnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, EconomySnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored economy bootstrap is invalid", exception);
        }
    }

    private String writeSnapshot(EconomySnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Economy bootstrap cannot be serialized", exception);
        }
    }
}
