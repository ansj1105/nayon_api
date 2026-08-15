package com.nayon.api.save;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcCloudSaveRepository implements CloudSaveRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcCloudSaveRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<CloudSave> findByAccountId(UUID accountId) {
        List<CloudSave> saves = jdbc.query("""
                select account_id, schema_version, revision, payload,
                       checksum, client_build, updated_at
                  from player_save_states
                 where account_id = ?
                """, this::mapSave, accountId);
        return saves.stream().findFirst();
    }

    @Override
    public CloudSave create(UUID accountId, SaveContent content) {
        lock("save-account:" + accountId);
        if (findByAccountId(accountId).isPresent()) {
            throw new SaveRevisionConflictException();
        }
        return jdbc.queryForObject("""
                insert into player_save_states(
                    account_id, schema_version, revision, payload,
                    checksum, client_build)
                values (?, ?, 1, ?::jsonb, ?, ?)
                returning account_id, schema_version, revision, payload,
                          checksum, client_build, updated_at
                """, this::mapSave,
                accountId,
                content.schemaVersion(),
                content.payload().toString(),
                content.checksum(),
                content.clientBuild());
    }

    @Override
    public Optional<CloudSave> updateIfRevision(
            UUID accountId, long expectedRevision, SaveContent content) {
        List<CloudSave> saves = jdbc.query("""
                update player_save_states
                   set schema_version = ?,
                       revision = revision + 1,
                       payload = ?::jsonb,
                       checksum = ?,
                       client_build = ?,
                       updated_at = now()
                 where account_id = ? and revision = ?
                returning account_id, schema_version, revision, payload,
                          checksum, client_build, updated_at
                """, this::mapSave,
                content.schemaVersion(),
                content.payload().toString(),
                content.checksum(),
                content.clientBuild(),
                accountId,
                expectedRevision);
        return saves.stream().findFirst();
    }

    @Override
    public Optional<SaveImportRecord> findImport(UUID requestId) {
        List<SaveImportRecord> imports = jdbc.query("""
                select account_id, request_id, source_checksum, result
                  from save_imports
                 where request_id = ? and status = 'COMPLETED'
                """, (rs, rowNumber) -> new SaveImportRecord(
                rs.getObject("account_id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getString("source_checksum"),
                readSave(rs.getString("result"))), requestId);
        return imports.stream().findFirst();
    }

    @Override
    public CloudSave importInitial(
            UUID accountId, UUID requestId, SaveContent content) {
        lock("save-import:" + requestId);
        lock("save-account:" + accountId);

        Optional<SaveImportRecord> replay = findImport(requestId);
        if (replay.isPresent()) {
            SaveImportRecord previous = replay.get();
            if (previous.accountId().equals(accountId)
                    && previous.checksum().equals(content.checksum())) {
                return previous.result();
            }
            throw new IdempotencyConflictException();
        }
        if (findByAccountId(accountId).isPresent()) {
            throw new SaveRevisionConflictException();
        }

        CloudSave saved = create(accountId, content);
        try {
            jdbc.update("""
                    insert into save_imports(
                        id, account_id, request_id, source_checksum,
                        status, result, completed_at)
                    values (?, ?, ?, ?, 'COMPLETED', ?::jsonb, now())
                    """,
                    UUID.randomUUID(),
                    accountId,
                    requestId,
                    content.checksum(),
                    writeSave(saved));
        } catch (DuplicateKeyException exception) {
            throw new IdempotencyConflictException();
        }
        return saved;
    }

    private void lock(String key) {
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> null,
                key);
    }

    private CloudSave mapSave(ResultSet rs, int rowNumber) throws SQLException {
        return new CloudSave(
                rs.getObject("account_id", UUID.class),
                rs.getInt("schema_version"),
                rs.getLong("revision"),
                readJson(rs.getString("payload")),
                rs.getString("checksum"),
                rs.getString("client_build"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored save JSON is invalid", exception);
        }
    }

    private CloudSave readSave(String json) {
        try {
            return objectMapper.readValue(json, CloudSave.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored import result is invalid", exception);
        }
    }

    private String writeSave(CloudSave save) {
        try {
            return objectMapper.writeValueAsString(save);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Import result cannot be serialized", exception);
        }
    }
}
