package com.nayon.api.integration;

import com.nayon.api.subscription.rtdn.GooglePlayRtdnMessage;
import com.nayon.api.subscription.rtdn.GooglePlayRtdnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "management.health.db.enabled=false")
@EnabledIfEnvironmentVariable(named = "E2E_DB", matches = "1")
class GooglePlayRtdnPostgresTest {

    @Autowired GooglePlayRtdnRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table google_play_rtdn_events");
    }

    @Test
    void messageIdIsIdempotentAndOnlyRetryableFailuresReopen() {
        GooglePlayRtdnMessage message = new GooglePlayRtdnMessage(
                "message-1", "com.korion.Nayon", 2, "token");

        assertThat(repository.begin(message, "a".repeat(64))).isTrue();
        repository.finish("message-1", "RETRYABLE_FAILED", "GOOGLE_PLAY_UNAVAILABLE");
        assertThat(repository.begin(message, "a".repeat(64))).isTrue();
        repository.finish("message-1", "PROCESSED", "UPDATED");
        assertThat(repository.begin(message, "a".repeat(64))).isFalse();

        assertThat(jdbc.queryForObject("""
                select processing_state from google_play_rtdn_events
                 where message_id = 'message-1'
                """, String.class)).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject(
                "select count(*) from google_play_rtdn_events", Long.class))
                .isEqualTo(1L);
    }
}
