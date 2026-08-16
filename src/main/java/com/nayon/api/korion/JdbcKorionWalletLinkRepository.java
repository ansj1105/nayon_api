package com.nayon.api.korion;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcKorionWalletLinkRepository implements KorionWalletLinkRepository {
    private static final String REQUEST_COLUMNS = """
            id, account_id, address, status, expires_at, failure_code,
            created_at, updated_at, completed_at
            """;
    private final JdbcTemplate jdbc;

    public JdbcKorionWalletLinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockAccount(UUID accountId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> null, "nayon-wallet-link:" + accountId);
    }

    @Override
    public void lockAddress(String address) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> null, "nayon-wallet-address:" + address);
    }

    @Override
    public Optional<KorionWalletLink> findLink(UUID accountId) {
        return jdbc.query("""
                select account_id, address, verified_request_id, verified_at
                  from player_korion_wallet_links where account_id = ?
                """, this::mapLink, accountId).stream().findFirst();
    }

    @Override
    public Optional<KorionWalletLink> findLinkByAddress(String address) {
        return jdbc.query("""
                select account_id, address, verified_request_id, verified_at
                  from player_korion_wallet_links where address = ?
                """, this::mapLink, address).stream().findFirst();
    }

    @Override
    public Optional<KorionWalletLinkRequest> findPending(UUID accountId) {
        return jdbc.query("select " + REQUEST_COLUMNS + " from korion_wallet_link_requests "
                        + "where account_id = ? and status = 'PENDING' order by created_at desc limit 1",
                this::mapRequest, accountId).stream().findFirst();
    }

    @Override
    public Optional<KorionWalletLinkRequest> findRequest(UUID accountId, UUID requestId) {
        return jdbc.query("select " + REQUEST_COLUMNS + " from korion_wallet_link_requests "
                        + "where account_id = ? and id = ?",
                this::mapRequest, accountId, requestId).stream().findFirst();
    }

    @Override
    public long countRequestsSince(UUID accountId, Instant since) {
        Long count = jdbc.queryForObject("""
                select count(*) from korion_wallet_link_requests
                 where account_id = ? and created_at >= ?
                """, Long.class, accountId, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    @Override
    public KorionWalletLinkRequest create(UUID id, UUID accountId, String address, Instant expiresAt) {
        return jdbc.queryForObject("""
                insert into korion_wallet_link_requests(
                    id, account_id, address, status, expires_at)
                values (?, ?, ?, 'PENDING', ?)
                returning
                """ + REQUEST_COLUMNS, this::mapRequest, id, accountId, address, Timestamp.from(expiresAt));
    }

    @Override
    public KorionWalletLinkRequest finish(UUID accountId, UUID requestId,
                                          KorionWalletLinkStatus status,
                                          Instant expiresAt, String failureCode) {
        return jdbc.queryForObject("""
                update korion_wallet_link_requests
                   set status = ?, expires_at = ?, failure_code = ?,
                       completed_at = case when ? = 'PENDING' then null else coalesce(completed_at, now()) end,
                       updated_at = now()
                 where account_id = ? and id = ?
                returning
                """ + REQUEST_COLUMNS, this::mapRequest,
                status.name(), Timestamp.from(expiresAt), failureCode, status.name(), accountId, requestId);
    }

    @Override
    public KorionWalletLink link(UUID accountId, UUID requestId, String address) {
        try {
            return jdbc.queryForObject("""
                    insert into player_korion_wallet_links(
                        account_id, address, verified_request_id, verified_at)
                    values (?, ?, ?, now())
                    on conflict (account_id) do update
                       set address = excluded.address,
                           verified_request_id = excluded.verified_request_id,
                           verified_at = excluded.verified_at,
                           updated_at = now()
                    returning account_id, address, verified_request_id, verified_at
                    """, this::mapLink, accountId, address, requestId);
        } catch (DuplicateKeyException exception) {
            throw new KorionWalletLinkException(
                    "KORION_ADDRESS_ALREADY_LINKED",
                    "The KORION wallet is already linked to another account.");
        }
    }

    @Override
    public void unlink(UUID accountId) {
        jdbc.update("delete from player_korion_wallet_links where account_id = ?", accountId);
        jdbc.update("""
                update korion_wallet_link_requests
                   set status = 'FAILED', failure_code = 'UNLINKED',
                       completed_at = now(), updated_at = now()
                 where account_id = ? and status = 'PENDING'
                """, accountId);
    }

    private KorionWalletLinkRequest mapRequest(ResultSet rs, int rowNumber) throws SQLException {
        return new KorionWalletLinkRequest(
                rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("address"), KorionWalletLinkStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("failure_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }

    private KorionWalletLink mapLink(ResultSet rs, int rowNumber) throws SQLException {
        return new KorionWalletLink(
                rs.getObject("account_id", UUID.class), rs.getString("address"),
                rs.getObject("verified_request_id", UUID.class), rs.getTimestamp("verified_at").toInstant());
    }
}
