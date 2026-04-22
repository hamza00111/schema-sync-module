package com.sync.cdc.spi;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Builds platform-specific SQL for upserts and deletes.
 *
 * <p>SQL Server uses T-SQL {@code MERGE} with {@code [bracket]} identifiers.
 * PostgreSQL uses {@code INSERT ... ON CONFLICT DO UPDATE} with {@code "double quote"} identifiers.
 */
public interface WriteDialect {

    /** Quote an identifier according to the target platform's rules. */
    String quoteIdentifier(String identifier);

    /**
     * Build a parameterized upsert (MERGE / ON CONFLICT) for the given table.
     * Parameter order in the returned SQL matches {@code allColumns}.
     */
    String buildUpsert(String tableName, List<String> allColumns, List<String> keyColumns);

    /**
     * Build a parameterized DELETE. Parameter order matches {@code keyColumns}.
     */
    String buildDelete(String tableName, List<String> keyColumns);

    /**
     * Validate a table name against the dialect's allowed character set to prevent injection
     * in the dynamic parts of {@link #buildUpsert} / {@link #buildDelete}.
     */
    void validateTableName(String tableName);

    /**
     * Stamp the current transaction as sync-origin so that the corresponding
     * {@link com.sync.cdc.spi.CdcSource} can flag the resulting change events with
     * {@link com.sync.model.ChangeEvent.Origin#SYNC}.
     *
     * <p>Called once per {@link com.sync.cdc.spi.SyncSink#dispatch} invocation, inside the sink's
     * transaction, <em>before</em> any writes. Default: no-op (for sinks that don't need
     * loop-prevention, e.g. REST, or platforms that haven't opted in yet).
     *
     * @param jdbc        the same template the sink is about to use
     * @param mappingName mapping name — useful for platforms that record it (e.g. SQL Server
     *                    marker table inserts the mapping name for diagnostics)
     */
    default void stampSyncOrigin(JdbcTemplate jdbc, String mappingName) {
        // default: no-op
    }
}
