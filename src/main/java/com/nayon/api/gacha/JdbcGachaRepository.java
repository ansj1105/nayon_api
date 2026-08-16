package com.nayon.api.gacha;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcGachaRepository implements GachaRepository {
    private final JdbcTemplate jdbc;
    private final EconomyRepository economyRepository;
    private final ObjectMapper objectMapper;

    public JdbcGachaRepository(
            JdbcTemplate jdbc,
            EconomyRepository economyRepository,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.economyRepository = economyRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public GachaDrawResult draw(
            UUID accountId,
            UUID requestId,
            String requestHash,
            GachaSpec spec,
            GachaEngine engine) {
        lock("gacha-account:" + accountId);
        lock("gacha-request:" + accountId + ':' + requestId);

        List<StoredDraw> existing = jdbc.query("""
                select request_hash, response_payload
                  from gacha_draws
                 where account_id = ? and request_id = ?
                """, (rs, rowNumber) -> new StoredDraw(
                rs.getString("request_hash"),
                readResult(rs.getString("response_payload"))),
                accountId, requestId);
        if (!existing.isEmpty()) {
            StoredDraw stored = existing.getFirst();
            if (!stored.requestHash().equals(requestHash)) {
                throw new GachaConflictException();
            }
            return stored.result().asReplay();
        }

        boolean bootstrapped = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from economy_bootstraps where account_id = ?)
                """, Boolean.class, accountId));
        if (!bootstrapped) {
            throw new EconomyNotBootstrappedException();
        }

        UUID drawId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        debit(accountId, requestId, drawId, spec);
        GachaPity currentPity = lockPity(accountId, spec.banner());
        GachaEngine.GachaOutcome outcome = engine.draw(spec, currentPity);

        jdbc.update("""
                insert into gacha_draws(
                    id, account_id, request_id, request_hash, banner_code,
                    payment_asset_type, payment_asset_code, payment_amount,
                    draw_count, response_payload, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?)
                """, drawId, accountId, requestId, requestHash,
                spec.banner().name(), spec.assetType(), spec.assetCode(),
                spec.amount(), spec.count(), java.sql.Timestamp.from(createdAt));

        int index = 0;
        for (GachaAward award : outcome.awards()) {
            jdbc.update("""
                    insert into player_equipment(
                        id, account_id, equipment_code, grade,
                        source_type, source_id)
                    values (?, ?, ?, ?, 'GACHA', ?)
                    """, award.equipmentId(), accountId, award.equipmentCode(),
                    award.grade(), drawId);
            jdbc.update("""
                    insert into gacha_draw_results(
                        draw_id, result_index, equipment_id,
                        equipment_code, grade, chroma)
                    values (?, ?, ?, ?, ?, ?)
                    """, drawId, index++, award.equipmentId(),
                    award.equipmentCode(), award.grade(), award.chroma());
        }

        if (spec.banner() == GachaBanner.CHROMA_SEASON_01) {
            jdbc.update("""
                    update gacha_pity_states
                       set hero_pity = ?, legendary_pity = ?,
                           version = version + 1, updated_at = now()
                     where account_id = ? and banner_code = ?
                    """, outcome.pity().hero(), outcome.pity().legendary(),
                    accountId, spec.banner().name());
        }

        EconomySnapshot economy = economyRepository.findSnapshot(accountId);
        GachaDrawResult result = new GachaDrawResult(
                drawId, spec.banner(), spec.payment(), spec.amount(),
                outcome.awards(), outcome.pity(), economy, createdAt, false);
        jdbc.update("""
                update gacha_draws set response_payload = ?::jsonb where id = ?
                """, writeResult(result), drawId);
        return result;
    }

    @Override
    public GachaHistoryPage history(UUID accountId, UUID before, int limit) {
        String cursorPredicate = before == null ? "" : """
                 and (created_at, id) < (
                     select created_at, id from gacha_draws
                      where id = ? and account_id = ?)
                """;
        List<GachaDrawResult> rows;
        if (before == null) {
            rows = jdbc.query("""
                    select response_payload from gacha_draws
                     where account_id = ?
                     order by created_at desc, id desc limit ?
                    """, (rs, rowNumber) -> readResult(rs.getString(1)),
                    accountId, limit + 1);
        } else {
            rows = jdbc.query("""
                    select response_payload from gacha_draws
                     where account_id = ?
                    """ + cursorPredicate + """
                     order by created_at desc, id desc limit ?
                    """, (rs, rowNumber) -> readResult(rs.getString(1)),
                    accountId, before, accountId, limit + 1);
        }
        boolean hasMore = rows.size() > limit;
        List<GachaDrawResult> page = hasMore
                ? new ArrayList<>(rows.subList(0, limit))
                : rows;
        UUID next = hasMore ? page.getLast().drawId() : null;
        return new GachaHistoryPage(page, next);
    }

    private void debit(
            UUID accountId, UUID requestId, UUID drawId, GachaSpec spec) {
        String table = spec.assetType().equals("CURRENCY")
                ? "player_wallets" : "player_items";
        String codeColumn = spec.assetType().equals("CURRENCY")
                ? "currency_code" : "item_code";
        String valueColumn = spec.assetType().equals("CURRENCY")
                ? "balance" : "quantity";
        List<Long> values = jdbc.query(
                "select " + valueColumn + " from " + table
                        + " where account_id = ? and " + codeColumn + " = ? for update",
                (rs, rowNumber) -> rs.getLong(1), accountId, spec.assetCode());
        long before = values.isEmpty() ? 0 : values.getFirst();
        if (before < spec.amount()) {
            throw new InsufficientAssetException(spec.assetCode());
        }
        long after = before - spec.amount();
        jdbc.update(
                "update " + table + " set " + valueColumn
                        + " = ?, version = version + 1, updated_at = now()"
                        + " where account_id = ? and " + codeColumn + " = ?",
                after, accountId, spec.assetCode());
        jdbc.update("""
                insert into economy_ledger(
                    id, account_id, asset_type, asset_code, delta,
                    balance_before, balance_after, reason_code,
                    reference_type, reference_id, request_id)
                values (?, ?, ?, ?, ?, ?, ?, 'GACHA_DRAW', 'GACHA', ?, ?)
                """, UUID.randomUUID(), accountId, spec.assetType(), spec.assetCode(),
                -spec.amount(), before, after, drawId, requestId);
    }

    private GachaPity lockPity(UUID accountId, GachaBanner banner) {
        if (banner != GachaBanner.CHROMA_SEASON_01) {
            return GachaPity.NONE;
        }
        jdbc.update("""
                insert into gacha_pity_states(account_id, banner_code)
                values (?, ?) on conflict do nothing
                """, accountId, banner.name());
        return jdbc.queryForObject("""
                select hero_pity, legendary_pity from gacha_pity_states
                 where account_id = ? and banner_code = ? for update
                """, (rs, rowNumber) -> new GachaPity(
                rs.getInt("hero_pity"), rs.getInt("legendary_pity")),
                accountId, banner.name());
    }

    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> null, key);
    }

    private GachaDrawResult readResult(String json) {
        try {
            return objectMapper.readValue(json, GachaDrawResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored gacha result is invalid", exception);
        }
    }

    private String writeResult(GachaDrawResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Gacha result cannot be serialized", exception);
        }
    }

    private record StoredDraw(String requestHash, GachaDrawResult result) {
    }
}
