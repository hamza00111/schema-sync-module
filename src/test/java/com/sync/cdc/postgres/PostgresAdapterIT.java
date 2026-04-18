package com.sync.cdc.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sync.core.SyncEngine;
import com.sync.core.SyncMapping;
import com.sync.model.ChangeEvent;
import com.sync.model.ChangeEvent.OperationType;
import com.sync.model.SyncCommand;
import com.sync.writer.SyncWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresAdapterIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private PostgresCdcSource cdcSource;
    private PostgresLsnStore lsnStore;
    private PostgresWriteDialect dialect;

    @BeforeAll
    void initSchema() throws Exception {
        POSTGRES.start();

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        this.jdbc = new JdbcTemplate(ds);

        // Run the library's setup script. We execute the whole file as one JDBC
        // statement so the $$ ... $$ function body is parsed correctly by the server.
        String setupSql = Files.readString(Path.of("sql/setup-postgres.sql"));
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute(setupSql);
        }

        // Domain table the test will sync from (source) ...
        jdbc.execute("""
                CREATE TABLE products (
                    id          BIGINT PRIMARY KEY,
                    name        TEXT NOT NULL,
                    price       NUMERIC(10,2) NOT NULL,
                    sync_source VARCHAR(10) NOT NULL DEFAULT 'APP'
                )
                """);

        // ... and the one it will sync to (target).
        jdbc.execute("""
                CREATE TABLE products_mirror (
                    id          BIGINT PRIMARY KEY,
                    name        TEXT NOT NULL,
                    price       NUMERIC(10,2) NOT NULL,
                    sync_source VARCHAR(10) NOT NULL DEFAULT 'APP'
                )
                """);

        jdbc.execute("""
                CREATE TRIGGER products_sync_audit
                    AFTER INSERT OR UPDATE OR DELETE ON products
                    FOR EACH ROW EXECUTE FUNCTION public.sync_record_change()
                """);

        this.cdcSource = new PostgresCdcSource(jdbc, new PostgresJsonReader(new ObjectMapper()));
        this.lsnStore = new PostgresLsnStore(jdbc);
        this.dialect = new PostgresWriteDialect();
    }

    @BeforeEach
    void resetState() {
        jdbc.update("TRUNCATE products, products_mirror, public.sync_audit, public.sync_tracking RESTART IDENTITY");
    }

    // --- CdcSource ----------------------------------------------------------

    @Test
    void inserts_areReturnedAsUpsertEvents() {
        jdbc.update("INSERT INTO products (id, name, price) VALUES (?, ?, ?)", 1L, "A", new BigDecimal("9.99"));
        jdbc.update("INSERT INTO products (id, name, price) VALUES (?, ?, ?)", 2L, "B", new BigDecimal("19.99"));

        List<ChangeEvent<Long>> events = cdcSource.getNetChanges("products", 0L, cdcSource.getMaxPosition());

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.operation() == OperationType.UPSERT);
        assertThat(events).extracting(e -> e.columns().get("name")).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void insertThenUpdate_collapsesToOneNetUpsert() {
        jdbc.update("INSERT INTO products (id, name, price) VALUES (?, ?, ?)", 1L, "A", new BigDecimal("9.99"));
        jdbc.update("UPDATE products SET price = ? WHERE id = ?", new BigDecimal("12.50"), 1L);

        List<ChangeEvent<Long>> events = cdcSource.getNetChanges("products", 0L, cdcSource.getMaxPosition());

        assertThat(events).hasSize(1);
        ChangeEvent<Long> only = events.get(0);
        assertThat(only.operation()).isEqualTo(OperationType.UPSERT);
        assertThat(((Number) only.columns().get("price")).doubleValue()).isEqualTo(12.50);
    }

    @Test
    void insertThenDelete_yieldsOneDeleteEvent() {
        jdbc.update("INSERT INTO products (id, name, price) VALUES (?, ?, ?)", 1L, "A", new BigDecimal("9.99"));
        jdbc.update("DELETE FROM products WHERE id = ?", 1L);

        List<ChangeEvent<Long>> events = cdcSource.getNetChanges("products", 0L, cdcSource.getMaxPosition());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).operation()).isEqualTo(OperationType.DELETE);
    }

    @Test
    void emptyRange_returnsEmptyList() {
        Long max = cdcSource.getMaxPosition();
        assertThat(cdcSource.getNetChanges("products", max, max)).isEmpty();
    }

    @Test
    void syncOriginated_isDetectableByMapping() {
        jdbc.update("INSERT INTO products (id, name, price, sync_source) VALUES (?, ?, ?, 'SYNC')",
                1L, "A", new BigDecimal("1"));

        List<ChangeEvent<Long>> events = cdcSource.getNetChanges("products", 0L, cdcSource.getMaxPosition());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).isSyncOriginated()).isTrue();
    }

    // --- LsnStore ----------------------------------------------------------

    @Test
    void lsnStore_initializeIfAbsent_isIdempotent() {
        lsnStore.initializeIfAbsent("mapping-x", 0L);
        lsnStore.initializeIfAbsent("mapping-x", 999L);

        assertThat(lsnStore.getLastPosition("mapping-x")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_tracking WHERE sync_name = ?", Long.class, "mapping-x"))
                .isOne();
    }

    @Test
    void lsnStore_updatePosition_advancesBookmarkAndCounter() {
        lsnStore.initializeIfAbsent("mapping-x", 0L);
        lsnStore.updatePosition("mapping-x", 42L, 3);
        lsnStore.updatePosition("mapping-x", 43L, 2);

        assertThat(lsnStore.getLastPosition("mapping-x")).isEqualTo(43L);
        Long synced = jdbc.queryForObject("SELECT changes_synced FROM sync_tracking WHERE sync_name = ?",
                Long.class, "mapping-x");
        assertThat(synced).isEqualTo(5L);
    }

    @Test
    void lsnStore_incrementErrors() {
        lsnStore.initializeIfAbsent("mapping-x", 0L);
        lsnStore.incrementErrors("mapping-x");
        lsnStore.incrementErrors("mapping-x");

        Long errors = jdbc.queryForObject("SELECT errors_count FROM sync_tracking WHERE sync_name = ?",
                Long.class, "mapping-x");
        assertThat(errors).isEqualTo(2L);
    }

    @Test
    void lsnStore_encodeDecode_roundTrip() {
        assertThat(lsnStore.decode(lsnStore.encode(123_456L))).isEqualTo(123_456L);
        assertThat(lsnStore.encode(null)).isNull();
        assertThat(lsnStore.decode(null)).isNull();
    }

    // --- WriteDialect executed end-to-end ----------------------------------

    @Test
    void writeDialect_upsert_insertsThenUpdates() {
        String upsert = dialect.buildUpsert(
                "products_mirror",
                List.of("id", "name", "price", "sync_source"),
                List.of("id"));

        jdbc.update(upsert, 7L, "seven", new BigDecimal("7.00"), "SYNC");
        assertThat(jdbc.queryForObject("SELECT name FROM products_mirror WHERE id = 7", String.class))
                .isEqualTo("seven");

        jdbc.update(upsert, 7L, "seven-v2", new BigDecimal("8.00"), "SYNC");
        Map<String, Object> row = jdbc.queryForMap("SELECT name, price, sync_source FROM products_mirror WHERE id = 7");
        assertThat(row.get("name")).isEqualTo("seven-v2");
        assertThat(row.get("price").toString()).startsWith("8.00");
        assertThat(row.get("sync_source")).isEqualTo("SYNC");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products_mirror WHERE id = 7", Long.class))
                .isOne();
    }

    @Test
    void writeDialect_delete_removesRow() {
        jdbc.update("INSERT INTO products_mirror (id, name, price) VALUES (?, ?, ?)", 1L, "x", new BigDecimal("1"));

        String sql = dialect.buildDelete("products_mirror", List.of("id"));
        jdbc.update(sql, 1L);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products_mirror", Long.class)).isZero();
    }

    // --- End-to-end through SyncEngine -------------------------------------

    @Test
    void syncEngine_mirrorsInsertAndUpdate_throughTriggersAndWriter() {
        SyncMapping<Long> mapping = new MirrorMapping();

        SyncWriter writer = new SyncWriter(jdbc, dialect);
        SyncEngine<Long> engine = new SyncEngine<>(cdcSource, lsnStore, writer);
        engine.initialize(mapping);

        // Write 2 source rows + update one of them — net: 2 upserts
        jdbc.update("INSERT INTO products (id, name, price) VALUES (?, ?, ?)", 1L, "A", new BigDecimal("1.00"));
        jdbc.update("INSERT INTO products (id, name, price) VALUES (?, ?, ?)", 2L, "B", new BigDecimal("2.00"));
        jdbc.update("UPDATE products SET price = ? WHERE id = ?", new BigDecimal("1.50"), 1L);

        SyncEngine.SyncResult<Long> result = engine.sync(mapping);

        assertThat(result.totalChanges()).isEqualTo(2);
        assertThat(result.commandsExecuted()).isEqualTo(2);
        assertThat(result.hasErrors()).isFalse();

        Map<Long, Map<String, Object>> mirror = jdbc.queryForList(
                "SELECT id, name, price FROM products_mirror ORDER BY id")
                .stream().collect(java.util.stream.Collectors.toMap(
                        r -> ((Number) r.get("id")).longValue(),
                        r -> r));

        assertThat(mirror.get(1L).get("name")).isEqualTo("A");
        assertThat(mirror.get(1L).get("price").toString()).startsWith("1.50");
        assertThat(mirror.get(2L).get("name")).isEqualTo("B");
    }

    @Test
    void syncEngine_skipsSyncOriginatedChanges() {
        SyncMapping<Long> mapping = new MirrorMapping();

        SyncWriter writer = new SyncWriter(jdbc, dialect);
        SyncEngine<Long> engine = new SyncEngine<>(cdcSource, lsnStore, writer);
        engine.initialize(mapping);

        jdbc.update("INSERT INTO products (id, name, price, sync_source) VALUES (?, ?, ?, 'SYNC')",
                1L, "A", new BigDecimal("1"));

        SyncEngine.SyncResult<Long> result = engine.sync(mapping);

        assertThat(result.totalChanges()).isEqualTo(1);
        assertThat(result.changesSkipped()).isEqualTo(1);
        assertThat(result.commandsExecuted()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products_mirror", Long.class)).isZero();
    }

    /** Mirrors `products` → `products_mirror` 1:1, skipping sync-originated changes. */
    private static class MirrorMapping implements SyncMapping<Long> {
        @Override public String name() { return "products_mirror"; }
        @Override public List<String> sourceCaptureInstances() { return List.of("products"); }
        @Override public SyncDirection direction() { return SyncDirection.LEGACY_TO_NEW; }

        @Override
        public List<SyncCommand> map(ChangeEvent<Long> event) {
            if (event.isSyncOriginated()) return List.of();

            Map<String, Object> row = event.columns();
            if (event.operation() == OperationType.DELETE) {
                return List.of(SyncCommand.delete(
                        "products_mirror",
                        Map.of("id", row.get("id")),
                        "id"));
            }
            return List.of(SyncCommand.upsert(
                    "products_mirror",
                    Map.of(
                            "id", row.get("id"),
                            "name", row.get("name"),
                            "price", row.get("price"),
                            "sync_source", "SYNC"
                    ),
                    "id"));
        }
    }
}
