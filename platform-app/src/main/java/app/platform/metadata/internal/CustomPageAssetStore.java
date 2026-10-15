package app.platform.metadata.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about the uploaded scripts of custom pages (Sprint 18, ADR-0127). A row says that a script with this
 * content exists in the organization; the bytes are in the object store. Rows are only ever added: a release that names
 * an asset can always be rolled back to, so nothing here removes one. Like the rest of the module it runs under the
 * tenant context that was open when the transaction began.
 */
@Repository
class CustomPageAssetStore {

    /** A stored script: its identifier, the SHA-256 of its bytes (lower case hexadecimal) and their number. */
    record Row(UUID id, String sha256, long size) {
    }

    private final JdbcClient jdbc;

    CustomPageAssetStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql("select id, sha256, size_bytes from custom_page_asset where id = :id and deleted_at is null")
                .param("id", id).query(CustomPageAssetStore::row).optional();
    }

    Optional<Row> findBySha256(String sha256) {
        return jdbc.sql("select id, sha256, size_bytes from custom_page_asset "
                + "where sha256 = :sha and deleted_at is null").param("sha", sha256)
                .query(CustomPageAssetStore::row).optional();
    }

    int count() {
        return jdbc.sql("select count(*) from custom_page_asset where deleted_at is null")
                .query(Integer.class).single();
    }

    Row insert(String sha256, int size, UUID actor) {
        return jdbc.sql("insert into custom_page_asset (sha256, size_bytes, created_by, updated_by) "
                        + "values (:sha, :size, :actor, :actor) returning id, sha256, size_bytes")
                .param("sha", sha256).param("size", size).param("actor", actor)
                .query(CustomPageAssetStore::row).single();
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("sha256"), rs.getLong("size_bytes"));
    }
}
