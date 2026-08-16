package com.nayon.api.accountlink;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAccountLinkRewardRepository implements AccountLinkRewardRepository {
    private final JdbcTemplate jdbc;

    public JdbcAccountLinkRewardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccountLinkRewardState> find(UUID accountId) {
        return jdbc.query("""
                select account_id, id, reward_claimed, reward_claimed_at
                  from player_account_link_rewards where account_id = ?
                """, this::map, accountId).stream().findFirst();
    }

    @Override
    public AccountLinkRewardState lockOrCreate(UUID accountId) {
        jdbc.update("""
                insert into player_account_link_rewards(account_id, id)
                values (?, ?) on conflict (account_id) do nothing
                """, accountId, UUID.randomUUID());
        return jdbc.queryForObject("""
                select account_id, id, reward_claimed, reward_claimed_at
                  from player_account_link_rewards
                 where account_id = ? for update
                """, this::map, accountId);
    }

    @Override
    public boolean hasGoogleIdentity(UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from auth_identities
                 where account_id = ? and provider = 'GOOGLE')
                """, Boolean.class, accountId));
    }

    @Override
    public boolean hasKorionWalletLink(UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from player_korion_wallet_links where account_id = ?)
                """, Boolean.class, accountId));
    }

    @Override
    public AccountLinkRewardState markClaimed(UUID accountId) {
        return jdbc.queryForObject("""
                update player_account_link_rewards
                   set reward_claimed = true,
                       reward_claimed_at = coalesce(reward_claimed_at, now()),
                       updated_at = now()
                 where account_id = ?
                returning account_id, id, reward_claimed, reward_claimed_at
                """, this::map, accountId);
    }

    private AccountLinkRewardState map(ResultSet rs, int rowNumber) throws SQLException {
        return new AccountLinkRewardState(
                rs.getObject("account_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getBoolean("reward_claimed"),
                rs.getTimestamp("reward_claimed_at") == null
                        ? null : rs.getTimestamp("reward_claimed_at").toInstant());
    }
}
