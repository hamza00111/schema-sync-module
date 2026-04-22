package com.sync.cdc.postgres;

import com.sync.cdc.spi.WriteDialect;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.stream.Collectors;

public class PostgresWriteDialect implements WriteDialect {

    @Override
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String buildUpsert(String tableName, List<String> allColumns, List<String> keyColumns) {
        validateTableName(tableName);

        List<String> nonKeyColumns = allColumns.stream()
                .filter(col -> !containsIgnoreCase(keyColumns, col))
                .toList();

        String columnList = allColumns.stream()
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));

        String placeholders = allColumns.stream()
                .map(c -> "?")
                .collect(Collectors.joining(", "));

        String conflictTarget = keyColumns.stream()
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(tableName)
                .append(" (").append(columnList).append(") ")
                .append("VALUES (").append(placeholders).append(") ")
                .append("ON CONFLICT (").append(conflictTarget).append(") ");

        if (nonKeyColumns.isEmpty()) {
            sql.append("DO NOTHING");
        } else {
            String updateSet = nonKeyColumns.stream()
                    .map(col -> quoteIdentifier(col) + " = EXCLUDED." + quoteIdentifier(col))
                    .collect(Collectors.joining(", "));
            sql.append("DO UPDATE SET ").append(updateSet);
        }

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
        if (!tableName.matches("[a-zA-Z0-9_.\"]+")) {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }
    }

    /**
     * Set the transaction-local GUC {@code sync.source} to {@code 'SYNC'}. The updated audit
     * trigger ({@code public.sync_record_change}) reads this and stamps the
     * {@code origin} column on every {@code sync_audit} row it writes within the same transaction.
     * {@code set_config(..., true)} means "local to the current transaction" — it resets on commit
     * so pool reuse is safe.
     */
    @Override
    public void stampSyncOrigin(JdbcTemplate jdbc, String mappingName) {
        jdbc.queryForObject("SELECT set_config('sync.source', 'SYNC', true)", String.class);
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) {
            if (s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }
}
