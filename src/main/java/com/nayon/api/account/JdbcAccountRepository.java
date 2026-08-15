package com.nayon.api.account;

import com.nayon.api.auth.AuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcAccountRepository implements AccountRepository {

    private final JdbcTemplate jdbc;

    public JdbcAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public PlayerAccount resolveOrCreate(
            AuthenticatedIdentity identity,
            PlayerAccount proposedAccount) {
        String lockKey = identity.provider().name() + ':' + identity.subject();
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> null,
                lockKey);

        List<PlayerAccount> existing = find(identity);
        if (!existing.isEmpty()) {
            jdbc.update("""
                    update auth_identities
                       set last_login_at = now()
                     where provider = ? and provider_subject = ?
                    """, identity.provider().name(), identity.subject());
            return existing.getFirst();
        }

        jdbc.update("""
                insert into player_accounts(
                    id, public_id, status, nickname, avatar_code,
                    frame_code, locale, created_at, updated_at, last_login_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """,
                proposedAccount.id(),
                proposedAccount.publicId(),
                proposedAccount.status().name(),
                proposedAccount.nickname(),
                proposedAccount.avatarCode(),
                proposedAccount.frameCode(),
                proposedAccount.locale(),
                java.sql.Timestamp.from(proposedAccount.createdAt()),
                java.sql.Timestamp.from(proposedAccount.createdAt()));
        jdbc.update("""
                insert into auth_identities(
                    id, account_id, provider, provider_subject)
                values (?, ?, ?, ?)
                """,
                java.util.UUID.randomUUID(),
                proposedAccount.id(),
                identity.provider().name(),
                identity.subject());
        return proposedAccount;
    }

    private List<PlayerAccount> find(AuthenticatedIdentity identity) {
        return jdbc.query("""
                select a.id, a.public_id, a.status, a.nickname,
                       a.avatar_code, a.frame_code, a.locale, a.created_at
                  from player_accounts a
                  join auth_identities i on i.account_id = a.id
                 where i.provider = ? and i.provider_subject = ?
                """, this::mapAccount, identity.provider().name(), identity.subject());
    }

    @Override
    public PlayerAccount updateProfile(UUID accountId, PlayerProfile profile) {
        List<PlayerAccount> updated = jdbc.query("""
                update player_accounts
                   set nickname = ?, avatar_code = ?, frame_code = ?, updated_at = now()
                 where id = ?
                returning id, public_id, status, nickname,
                          avatar_code, frame_code, locale, created_at
                """, this::mapAccount,
                profile.nickname(), profile.avatarCode(), profile.frameCode(), accountId);
        if (updated.isEmpty()) {
            throw new IllegalArgumentException("account does not exist");
        }
        return updated.getFirst();
    }

    private PlayerAccount mapAccount(ResultSet rs, int rowNumber) throws SQLException {
        return new PlayerAccount(
                rs.getObject("id", java.util.UUID.class),
                rs.getString("public_id"),
                AccountStatus.valueOf(rs.getString("status")),
                rs.getString("nickname"),
                rs.getString("avatar_code"),
                rs.getString("frame_code"),
                rs.getString("locale"),
                rs.getTimestamp("created_at").toInstant());
    }
}
