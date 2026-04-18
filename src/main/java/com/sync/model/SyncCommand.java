package com.sync.model;

import java.util.Map;

/**
 * Represents a write operation to be applied to a target table.
 * Produced by SyncMapping.map() and executed by SyncWriter.
 */
public record SyncCommand(
        /* Target table (schema-qualified, e.g., "dbo.NewOrders") */
        String targetTable,

        /** The type of write operation */
        CommandType type,

        /**
         * Column name -> value map for the target table.
         * For UPSERT: all columns to write.
         * For DELETE: only the key columns needed to identify the row.
         */
        Map<String, Object> values,

        /** Column names that form the primary/unique key for matching */
        String[] keyColumns
) {

    public static SyncCommand upsert(String targetTable, Map<String, Object> values, String... keyColumns) {
        return new SyncCommand(targetTable, CommandType.UPSERT, values, keyColumns);
    }

    public static SyncCommand delete(String targetTable, Map<String, Object> keyValues, String... keyColumns) {
        return new SyncCommand(targetTable, CommandType.DELETE, keyValues, keyColumns);
    }

    public enum CommandType {
        UPSERT,
        DELETE
    }
}
