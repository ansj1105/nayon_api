package com.nayon.api.share;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcShareRewardRepository implements ShareRewardRepository {

    private static final String COLUMNS = """
            account_id, id, shared, reward_claimed, shared_at,
            reward_claimed_at, share_target
            """;

    private final JdbcTemplate jdbc;

    public JdbcShareRewardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ShareRewardState> findByAccountId(UUID accountId) {
        List<ShareRewardState> values = jdbc.query(
                "select " + COLUMNS + " from player_share_rewards where account_id = ?",
                this::map, accountId);
        return values.stream().findFirst();
    }

    @Override
    public ShareRewardState markOpened(UUID accountId, String target) {
        return jdbc.queryForObject("""
                insert into player_share_rewards(
                    account_id, id, shared, shared_at, share_target)
                values (?, ?, true, now(), ?)
                on conflict (account_id) do update
                   set shared = true,
                       shared_at = coalesce(player_share_rewards.shared_at, excluded.shared_at),
                       share_target = coalesce(player_share_rewards.share_target, excluded.share_target),
                       updated_at = now()
                returning
                """ + COLUMNS,
                this::map, accountId, UUID.randomUUID(), target);
    }

    @Override
    public ShareRewardState lockOrCreate(UUID accountId) {
        jdbc.update("""
                insert into player_share_rewards(account_id, id)
                values (?, ?)
                on conflict (account_id) do nothing
                """, accountId, UUID.randomUUID());
        return jdbc.queryForObject(
                "select " + COLUMNS
                        + " from player_share_rewards where account_id = ? for update",
                this::map, accountId);
    }

    @Override
    public ShareRewardState markClaimed(UUID accountId) {
        return jdbc.queryForObject("""
                update player_share_rewards
                   set reward_claimed = true,
                       reward_claimed_at = coalesce(reward_claimed_at, now()),
                       updated_at = now()
                 where account_id = ?
                returning
                """ + COLUMNS,
                this::map, accountId);
    }

    private ShareRewardState map(ResultSet rs, int rowNumber) throws SQLException {
        return new ShareRewardState(
                rs.getObject("account_id", UUID.class),
                rs.getObject("id", UUID.class),
                rs.getBoolean("shared"),
                rs.getBoolean("reward_claimed"),
                rs.getTimestamp("shared_at") == null
                        ? null : rs.getTimestamp("shared_at").toInstant(),
                rs.getTimestamp("reward_claimed_at") == null
                        ? null : rs.getTimestamp("reward_claimed_at").toInstant(),
                rs.getString("share_target"));
    }
}
