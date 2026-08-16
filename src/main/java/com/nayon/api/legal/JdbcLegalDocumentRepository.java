package com.nayon.api.legal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcLegalDocumentRepository implements LegalDocumentRepository {
    private final JdbcTemplate jdbc;

    public JdbcLegalDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LegalDocument> findActive(LegalDocumentType type, String locale) {
        List<LegalDocument> values = jdbc.query("""
                select id, document_type, locale, version, title, content,
                       effective_at, published_at
                  from legal_documents
                 where document_type = ?
                   and locale = ?
                   and active
                   and effective_at <= now()
                 order by effective_at desc
                 limit 1
                """, this::map, type.name(), locale);
        return values.stream().findFirst();
    }

    private LegalDocument map(ResultSet rs, int rowNumber) throws SQLException {
        return new LegalDocument(
                rs.getObject("id", java.util.UUID.class),
                LegalDocumentType.valueOf(rs.getString("document_type")),
                rs.getString("locale"),
                rs.getString("version"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getTimestamp("effective_at").toInstant(),
                rs.getTimestamp("published_at").toInstant());
    }
}
