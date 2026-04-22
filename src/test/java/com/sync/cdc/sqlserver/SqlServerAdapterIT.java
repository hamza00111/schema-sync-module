package com.sync.cdc.sqlserver;

import com.sync.core.SyncEngine;
import com.sync.core.SyncMapping;
import com.sync.model.ChangeEvent;
import com.sync.model.ChangeEvent.OperationType;
import com.sync.model.SyncCommand;
import com.sync.writer.JdbcSyncSink;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SQL Server CDC runs asynchronously — the SQL Server Agent capture job polls the
 * transaction log at an interval (~5s) before populating the CDC tables. Tests
 * therefore write rows then poll {@code CdcSource.getNetChanges} until the capture
 * catches up, within a generous timeout.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlServerAdapterIT {

    @Container
    static final MSSQLServerContainer<?> MSSQL = new MSSQLServerContainer<>(
            DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
            .withEnv("MSSQL_AGENT_ENABLED", "true")
            .acceptLicense();

    private static final Duration CDC_CAPTURE_TIMEOUT = Duration.ofSeconds(60);

    private JdbcTemplate jdbc;
    private SqlServerCdcSource cdcSource;
    private SqlServerLsnStore lsnStore;
    private SqlServerWriteDialect dialect;

    @BeforeAll
    void initSchema() {
        MSSQL.start();

        // 1. Create a user DB (CDC cannot be enabled on master) via a master-scoped connection.
        try (var conn = java.sql.DriverManager.getConnection(
                MSSQL.getJdbcUrl(), MSSQL.getUsername(), MSSQL.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("IF DB_ID('syncdb') IS NULL CREATE DATABASE syncdb");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create syncdb", e);
        }

        // 2. JdbcTemplate pointed at syncdb.
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(MSSQL.getJdbcUrl() + ";databaseName=syncdb");
        ds.setUsername(MSSQL.getUsername());
        ds.setPassword(MSSQL.getPassword());
        ds.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        this.jdbc = new JdbcTemplate(ds);

        // 3. Enable CDC on the DB and create the tracking table.
        jdbc.execute("EXEC sys.sp_cdc_enable_db");
        jdbc.execute("""
                CREATE TABLE dbo.SyncTracking (
                    sync_name       VARCHAR(100) PRIMARY KEY,
                    last_lsn        BINARY(10)   NOT NULL,
                    last_sync_time  DATETIME2    NOT NULL DEFAULT GETDATE(),
                    changes_synced  BIGINT       NOT NULL DEFAULT 0,
                    errors_count    BIGINT       NOT NULL DEFAULT 0
                )
                """);

        // 4. Source and target tables with sync_source convention.
        jdbc.execute("""
                CREATE TABLE dbo.products (
                    id          BIGINT PRIMARY KEY,
                    name        NVARCHAR(100) NOT NULL,
                    price       DECIMAL(10,2) NOT NULL,
                    sync_source VARCHAR(10)   NOT NULL DEFAULT 'APP'
                )
                """);
        jdbc.execute("""
                CREATE TABLE dbo.products_mirror (
                    id          BIGINT PRIMARY KEY,
                    name        NVARCHAR(100) NOT NULL,
                    price       DECIMAL(10,2) NOT NULL,
                    sync_source VARCHAR(10)   NOT NULL DEFAULT 'APP'
                )
                """);

        jdbc.execute("""
                EXEC sys.sp_cdc_enable_table
                    @source_schema       = N'dbo',
                    @source_name         = N'products',
                    @role_name           = NULL,
                    @supports_net_changes = 1
                """);

        this.cdcSource = new SqlServerCdcSource(jdbc);
        this.lsnStore = new SqlServerLsnStore(jdbc, SqlServerLsnStore.DEFAULT_TRACKING_TABLE);
        this.dialect = new SqlServerWriteDialect();

        // 5. Wait until the capture job has initialized so getMaxLsn returns non-null.
        await().atMost(CDC_CAPTURE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> {
                    byte[] max = jdbc.queryForObject("SELECT sys.fn_cdc_get_max_lsn()", byte[].class);
                    return max != null;
                });
    }

    // --- CdcSource ----------------------------------------------------------

    @Test
    void insertsAreCapturedAsUpsertEvents() {
        clearSourceTables();
        ByteArrayPosition from = cdcSource.getMaxPosition();

        jdbc.update("INSERT INTO dbo.products (id, name, price) VALUES (?, ?, ?)", 1L, "A", new BigDecimal("9.99"));
        jdbc.update("INSERT INTO dbo.products (id, name, price) VALUES (?, ?, ?)", 2L, "B", new BigDecimal("19.99"));

        List<ChangeEvent<ByteArrayPosition>> events = waitForChanges("dbo_products", from, 2);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.operation() == OperationType.UPSERT);
        assertThat(events).extracting(e -> e.columns().get("name")).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void insertThenUpdate_collapsesToOneNetUpsert() {
        clearSourceTables();
        ByteArrayPosition from = cdcSource.getMaxPosition();

        jdbc.update("INSERT INTO dbo.products (id, name, price) VALUES (?, ?, ?)", 10L, "foo", new BigDecimal("1.00"));
        jdbc.update("UPDATE dbo.products SET price = ? WHERE id = ?", new BigDecimal("2.50"), 10L);

        List<ChangeEvent<ByteArrayPosition>> events = waitForChanges("dbo_products", from, 1);

        assertThat(events).hasSize(1);
        ChangeEvent<ByteArrayPosition> only = events.get(0);
        assertThat(only.operation()).isEqualTo(OperationType.UPSERT);
        assertThat(((Number) only.columns().get("price")).doubleValue()).isEqualTo(2.50);
    }

    @Test
    void insertThenDelete_yieldsNothingInNetChanges() {
        // Net changes with "all with merge" collapses insert+delete to no change.
        clearSourceTables();
        ByteArrayPosition from = cdcSource.getMaxPosition();

        jdbc.update("INSERT INTO dbo.products (id, name, price) VALUES (?, ?, ?)", 20L, "tmp", new BigDecimal("1"));
        jdbc.update("DELETE FROM dbo.products WHERE id = ?", 20L);

        // Wait for capture to catch up by letting some time pass, then read.
        await().atMost(CDC_CAPTURE_TIMEOUT).pollInterval(Duration.ofSeconds(1))
                .until(() -> cdcSource.getMaxPosition().compareTo(from) > 0);

        List<ChangeEvent<ByteArrayPosition>> events =
                cdcSource.getNetChanges("dbo_products", from, cdcSource.getMaxPosition());
        assertThat(events).isEmpty();
    }

    @Test
    void syncOriginated_isDetectableByMapping() {
        clearSourceTables();
        ByteArrayPosition from = cdcSource.getMaxPosition();

        jdbc.update("INSERT INTO dbo.products (id, name, price, sync_source) VALUES (?, ?, ?, 'SYNC')",
                30L, "S", new BigDecimal("1"));

        List<ChangeEvent<ByteArrayPosition>> events = waitForChanges("dbo_products", from, 1);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).isSyncOriginated()).isTrue();
    }

    // --- LsnStore -----------------------------------------------------------

    @Test
    void lsnStore_initializeIfAbsent_isIdempotent() {
        ByteArrayPosition zero = new ByteArrayPosition(new byte[10]);
        lsnStore.initializeIfAbsent("mapping-a", zero);
        lsnStore.initializeIfAbsent("mapping-a", new ByteArrayPosition(new byte[]{0,0,0,0,0,0,0,0,0,1}));

        ByteArrayPosition stored = lsnStore.getLastPosition("mapping-a");
        assertThat(stored).isEqualTo(zero);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.SyncTracking WHERE sync_name = ?",
                Integer.class, "mapping-a")).isOne();
    }

    @Test
    void lsnStore_updatePosition_advancesBookmarkAndCounter() {
        ByteArrayPosition zero = new ByteArrayPosition(new byte[10]);
        ByteArrayPosition later = new ByteArrayPosition(new byte[]{0,0,0,0,0,0,0,0,0,42});
        lsnStore.initializeIfAbsent("mapping-b", zero);
        lsnStore.updatePosition("mapping-b", later, 7);

        assertThat(lsnStore.getLastPosition("mapping-b")).isEqualTo(later);
        Long synced = jdbc.queryForObject(
                "SELECT changes_synced FROM dbo.SyncTracking WHERE sync_name = ?", Long.class, "mapping-b");
        assertThat(synced).isEqualTo(7L);
    }

    @Test
    void lsnStore_incrementErrors() {
        lsnStore.initializeIfAbsent("mapping-c", new ByteArrayPosition(new byte[10]));
        lsnStore.incrementErrors("mapping-c");
        lsnStore.incrementErrors("mapping-c");

        Long errors = jdbc.queryForObject(
                "SELECT errors_count FROM dbo.SyncTracking WHERE sync_name = ?", Long.class, "mapping-c");
        assertThat(errors).isEqualTo(2L);
    }

    @Test
    void lsnStore_encodeDecode_roundTrip() {
        ByteArrayPosition p = new ByteArrayPosition(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        assertThat(lsnStore.decode(lsnStore.encode(p))).isEqualTo(p);
    }

    // --- WriteDialect executed end-to-end ----------------------------------

    @Test
    void writeDialect_upsert_insertsThenUpdates() {
        String upsert = dialect.buildUpsert(
                "dbo.products_mirror",
                List.of("id", "name", "price", "sync_source"),
                List.of("id"));

        jdbc.update(upsert, 77L, "seven", new BigDecimal("7.00"), "SYNC");
        assertThat(jdbc.queryForObject("SELECT name FROM dbo.products_mirror WHERE id = 77", String.class))
                .isEqualTo("seven");

        jdbc.update(upsert, 77L, "seven-v2", new BigDecimal("8.00"), "SYNC");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, price, sync_source FROM dbo.products_mirror WHERE id = 77");
        assertThat(row.get("name")).isEqualTo("seven-v2");
        assertThat(((Number) row.get("price")).doubleValue()).isEqualTo(8.00);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.products_mirror WHERE id = 77", Integer.class))
                .isOne();
    }

    @Test
    void writeDialect_delete_removesRow() {
        jdbc.update("INSERT INTO dbo.products_mirror (id, name, price) VALUES (?, ?, ?)",
                555L, "x", new BigDecimal("1"));

        String sql = dialect.buildDelete("dbo.products_mirror", List.of("id"));
        jdbc.update(sql, 555L);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.products_mirror WHERE id = 555", Integer.class))
                .isZero();
    }

    // --- End-to-end through SyncEngine -------------------------------------

    @Test
    void syncEngine_mirrorsInsertAndUpdate_throughCdcAndWriter() {
        clearSourceTables();

        SyncMapping<ByteArrayPosition> mapping = new MirrorMapping();
        JdbcSyncSink sink = new JdbcSyncSink("default", jdbc, dialect);
        SyncEngine<ByteArrayPosition> engine = new SyncEngine<>(cdcSource, lsnStore);
        // Re-initialize by upserting the tracking row manually to the current max (skip backlog)
        ByteArrayPosition start = cdcSource.getMaxPosition();
        jdbc.update("DELETE FROM dbo.SyncTracking WHERE sync_name = ?", "products_mirror");
        lsnStore.initializeIfAbsent("products_mirror", start);

        jdbc.update("INSERT INTO dbo.products (id, name, price) VALUES (?, ?, ?)", 100L, "A", new BigDecimal("1.00"));
        jdbc.update("INSERT INTO dbo.products (id, name, price) VALUES (?, ?, ?)", 200L, "B", new BigDecimal("2.00"));
        jdbc.update("UPDATE dbo.products SET price = ? WHERE id = ?", new BigDecimal("1.50"), 100L);

        // Wait for capture to progress past `start`, then run the engine.
        await().atMost(CDC_CAPTURE_TIMEOUT).pollInterval(Duration.ofSeconds(1))
                .until(() -> cdcSource.getMaxPosition().compareTo(start) > 0);

        SyncEngine.SyncResult<ByteArrayPosition> result = engine.sync(mapping, sink);

        assertThat(result.totalChanges()).isEqualTo(2);
        assertThat(result.commandsExecuted()).isEqualTo(2);
        assertThat(result.hasErrors()).isFalse();

        Map<String, Object> mirrorRow100 = jdbc.queryForMap("SELECT name, price FROM dbo.products_mirror WHERE id = 100");
        assertThat(mirrorRow100.get("name")).isEqualTo("A");
        assertThat(((Number) mirrorRow100.get("price")).doubleValue()).isEqualTo(1.50);

        Map<String, Object> mirrorRow200 = jdbc.queryForMap("SELECT name, price FROM dbo.products_mirror WHERE id = 200");
        assertThat(mirrorRow200.get("name")).isEqualTo("B");
    }

    // --- helpers ------------------------------------------------------------

    private void clearSourceTables() {
        jdbc.execute("DELETE FROM dbo.products");
        jdbc.execute("DELETE FROM dbo.products_mirror");
    }

    /**
     * Poll until the capture agent has written at least {@code expectedCount} net changes
     * since {@code from}, then return them.
     */
    private List<ChangeEvent<ByteArrayPosition>> waitForChanges(
            String captureInstance, ByteArrayPosition from, int expectedCount) {
        @SuppressWarnings("unchecked")
        List<ChangeEvent<ByteArrayPosition>>[] holder = new List[]{List.of()};
        await().atMost(CDC_CAPTURE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> {
                    ByteArrayPosition max = cdcSource.getMaxPosition();
                    if (max.compareTo(from) <= 0) return false;
                    holder[0] = cdcSource.getNetChanges(captureInstance, from, max);
                    return holder[0].size() >= expectedCount;
                });
        return holder[0];
    }

    /** Mirrors `dbo.products` → `dbo.products_mirror`. */
    private static class MirrorMapping implements SyncMapping<ByteArrayPosition> {
        @Override public String name() { return "products_mirror"; }
        @Override public List<String> sourceCaptureInstances() { return List.of("dbo_products"); }
        @Override public SyncDirection direction() { return SyncDirection.LEGACY_TO_NEW; }

        @Override
        public List<SyncCommand> map(ChangeEvent<ByteArrayPosition> event) {
            if (event.isSyncOriginated()) return List.of();

            Map<String, Object> row = event.columns();
            if (event.operation() == OperationType.DELETE) {
                return List.of(SyncCommand.delete(
                        "dbo.products_mirror",
                        Map.of("id", row.get("id")),
                        "id"));
            }
            return List.of(SyncCommand.upsert(
                    "dbo.products_mirror",
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
