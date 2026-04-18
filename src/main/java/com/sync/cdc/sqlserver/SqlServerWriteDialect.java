package com.sync.cdc.sqlserver;

import com.sync.cdc.spi.WriteDialect;

import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

public class SqlServerWriteDialect implements WriteDialect {

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
