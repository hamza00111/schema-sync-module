package com.sync.model;

import java.util.Map;

/**
 * Represents a single change captured by a {@code CdcSource}.
 *
 * <p>The type parameter {@code P} is the platform-specific position type
 * (byte[] wrapper for SQL Server LSN, Long for a Postgres audit-table id, etc.).
 *
 * @param <P> the position type for this platform
 */
public record ChangeEvent<P extends Comparable<P>>(
        /* The capture instance this change came from (SQL Server CDC name, or a Postgres table name). */
        String captureInstance,

        /* The operation type. */
        OperationType operation,

        /* Column name -> value map from the change row. */
        Map<String, Object> columns,

        /* Position assigned by the source for this change. */
        P position
) {

    /**
     * Convenience accessor for a column value with type casting.
     */
    public <T> T get(String column, Class<T> type) {
        Object value = columns.get(column);
        if (value == null) return null;
        return type.cast(value);
    }

    public String getString(String column) {
        return get(column, String.class);
    }

    public Long getLong(String column) {
        Object val = columns.get(column);
        if (val == null) return null;
        if (val instanceof Long l) return l;
        if (val instanceof Integer i) return i.longValue();
        return Long.parseLong(val.toString());
    }

    /**
     * Check if this change was originated by the sync process itself.
     * Convention: the source table must have a {@code sync_source} column.
     */
    public boolean isSyncOriginated() {
        Object syncSource = columns.get("sync_source");
        return "SYNC".equals(syncSource);
    }

    public enum OperationType {
        DELETE(1),
        INSERT(2),
        UPDATE_BEFORE(3),  // pre-image of update
        UPDATE_AFTER(4),   // post-image of update
        UPSERT(5);         // net changes "all with merge" mode

        private final int cdcCode;

        OperationType(int cdcCode) {
            this.cdcCode = cdcCode;
        }

        public int cdcCode() {
            return cdcCode;
        }

        public static OperationType fromCdcCode(int code) {
            for (OperationType op : values()) {
                if (op.cdcCode == code) return op;
            }
            throw new IllegalArgumentException("Unknown CDC operation code: " + code);
        }
    }
}
