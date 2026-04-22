package com.sync.cdc.sqlserver;

import com.sync.cdc.spi.WriteDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class SqlServerWriteDialect implements WriteDialect {

    private static final Logger log = LoggerFactory.getLogger(SqlServerWriteDialect.class);

    /**
     * Sidecar marker table. A row is inserted here inside the sync sink's transaction so that all
     * user-table writes in the same transaction share its {@code __$start_lsn} — the CDC source
     * uses that to flag matching change events as sync-origin. Must be CDC-enabled (see
     * {@code sql/setup-sqlserver.sql}).
     */
    public static final String MARKER_TABLE = "dbo.sync_markers";

    private final AtomicBoolean stampWarningLogged = new AtomicBoolean();

    @Override
    public String quoteIdentifier(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }

    @Override
    public String buildUpsert(String tableName, List<String> allColumns, List<String> keyColumns) {
        validateTableName(tableName);

        List<String> nonKeyColumns = allColumns.stream()
                .filter(col -> !containsIgnoreCase(keyColumns, col))
                .toList();

        String sourceSelect = allColumns.stream()
                .map(col -> "? AS " + quoteIdentifier(col))
                .collect(Collectors.joining(", "));

        String onClause = buildKeyMatchClause(keyColumns);

        String updateSet = nonKeyColumns.stream()
                .map(col -> "target." + quoteIdentifier(col) + " = source." + quoteIdentifier(col))
                .collect(Collectors.joining(", "));

        String insertColumns = allColumns.stream()
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
        String insertValues = allColumns.stream()
                .map(col -> "source." + quoteIdentifier(col))
                .collect(Collectors.joining(", "));

        StringBuilder sql = new StringBuilder();
        sql.append("MERGE ").append(tableName).append(" AS target ");
        sql.append("USING (SELECT ").append(sourceSelect).append(") AS source ");
        sql.append("ON (").append(onClause).append(") ");

        if (!nonKeyColumns.isEmpty()) {
            sql.append("WHEN MATCHED THEN UPDATE SET ").append(updateSet).append(" ");
        }

        sql.append("WHEN NOT MATCHED THEN INSERT (").append(insertColumns)
                .append(") VALUES (").append(insertValues).append(");");

        return sql.toString();
    }

    @Override
    public String buildDelete(String tableName, List<String> keyColumns) {
        validateTableName(tableName);

        String whereClause = keyColumns.stream()
                .map(col -> quoteIdentifier(col) + " = ?")
                .collect(Collectors.joining(" AND "));

        return "DELETE FROM " + tableName + " WHERE " + whereClause;
    }

    @Override
    public void validateTableName(String tableName) {
        if (!tableName.matches("[a-zA-Z0-9_.\\[\\]]+")) {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }
    }

    @Override
    public void stampSyncOrigin(JdbcTemplate jdbc, String mappingName) {
        try {
            jdbc.update("INSERT INTO " + MARKER_TABLE + " (sync_name) VALUES (?)", mappingName);
        } catch (DataAccessException e) {
            // Marker table absent (pre-upgrade deployment): fall back to the legacy
            // `sync_source` column convention. Warn once so operators know to run the updated
            // setup script if they want the column-less mechanism.
            if (stampWarningLogged.compareAndSet(false, true)) {
                log.warn("{} is missing or not writable — falling back to the `sync_source` " +
                        "column convention. Apply sql/setup-sqlserver.sql to enable the " +
                        "column-less loop-prevention path. Cause: {}", MARKER_TABLE, e.getMessage());
            }
        }
    }

    private String buildKeyMatchClause(List<String> keyColumns) {
        StringJoiner joiner = new StringJoiner(" AND ");
        for (String key : keyColumns) {
            joiner.add("target." + quoteIdentifier(key) + " = source." + quoteIdentifier(key));
        }
        return joiner.toString();
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) {
            if (s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }
}
