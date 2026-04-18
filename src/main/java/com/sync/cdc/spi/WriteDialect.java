package com.sync.cdc.spi;

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
}
