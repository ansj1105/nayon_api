package com.nayon.api.subscription.rtdn;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class GooglePlayRtdnRepository {

    private final JdbcTemplate jdbc;

    public GooglePlayRtdnRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean begin(GooglePlayRtdnMessage message, String tokenHash) {
        lock("google-rtdn:" + message.messageId());
        List<String> states = jdbc.query("""
                select processing_state from google_play_rtdn_events
                 where message_id = ? for update
                """, (rs, rowNumber) -> rs.getString(1), message.messageId());
        if (!states.isEmpty()) {
            if ("RETRYABLE_FAILED".equals(states.getFirst())) {
                jdbc.update("""
                        update google_play_rtdn_events
                           set processing_state = 'PROCESSING',
                               result_code = null, processed_at = null
                         where message_id = ?
                        """, message.messageId());
                return true;
            }
            return false;
        }
        jdbc.update("""
                insert into google_play_rtdn_events(
                    message_id, package_name, notification_type,
                    purchase_token_hash, processing_state)
                values (?, ?, ?, ?, 'PROCESSING')
                """, message.messageId(), message.packageName(),
                message.notificationType(), tokenHash);
        return true;
    }

    @Transactional
    public void finish(String messageId, String state, String resultCode) {
        jdbc.update("""
                update google_play_rtdn_events
                   set processing_state = ?, result_code = ?, processed_at = now()
                 where message_id = ? and processing_state = 'PROCESSING'
                """, state, resultCode, messageId);
    }

    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { }, key);
    }
}
