package com.sync.cdc.postgres;

import com.sync.cdc.spi.CdcSource;
import com.sync.model.ChangeEvent;
import com.sync.model.ChangeEvent.OperationType;
import com.sync.model.ChangeEvent.Origin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reads changes from a trigger-maintained audit table ({@code public.sync_audit}).
 *
 * <p>Position type is {@link Long} — the audit row id (bigserial), strictly monotonic.
 * "Capture instance" is the source table name.
 */
public class PostgresCdcSource implements CdcSource<Long> {

    private static final Logger log = LoggerFactory.getLogger(PostgresCdcSource.class);

    private final JdbcTemplate jdbc;
    private final PostgresJsonReader jsonReader;

    public PostgresCdcSource(JdbcTemplate jdbc, PostgresJsonReader jsonReader) {
        this.jdbc = jdbc;
        this.jsonReader = jsonReader;
    }

    @Override
    public Long getMaxPosition() {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM public.sync_audit", Long.class);
        return max == null ? 0L : max;
    }

    @Override
    public Long getMinPosition(String captureInstance) {
        Long min = jdbc.queryForObject(
                "SELECT COALESCE(MIN(id), 0) FROM public.sync_audit WHERE table_name = ?",
                Long.class, captureInstance);
        return min == null ? 0L : min;
    }

    @Override
    public List<ChangeEvent<Long>> getNetChanges(String captureInstance, Long from, Long to) {
        if (from.compareTo(to) >= 0) {
            return Collections.emptyList();
        }

        // DISTINCT ON collapses to the latest change per primary key.
        String sql = """
                SELECT id, op, row_json, pk_json, origin
                FROM (
                    SELECT DISTINCT ON (pk_json) id, op, row_json, pk_json, origin
                    FROM public.sync_audit
                    WHERE table_name = ? AND id > ? AND id <= ?
                    ORDER BY pk_json, id DESC
                ) t
                ORDER BY id
                """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, captureInstance, from, to);

        log.debug("Capture [{}]: read {} net change(s) in range ({}, {}]",
                captureInstance, rows.size(), from, to);

        return rows.stream()
                .map(row -> toChangeEvent(captureInstance, row))
                .toList();
    }

    private ChangeEvent<Long> toChangeEvent(String captureInstance, Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        String op = (String) row.get("op");
        Object rowJson = row.get("row_json");

        Map<String, Object> columns = jsonReader.readObject(rowJson);

        return new ChangeEvent<>(
                captureInstance,
                decodeOperation(op),
                columns,
                id,
                decodeOrigin((String) row.get("origin"))
        );
    }

    private static OperationType decodeOperation(String op) {
        return switch (op) {
            case "I", "U" -> OperationType.UPSERT;
            case "D"      -> OperationType.DELETE;
            default       -> throw new IllegalArgumentException("Unknown op: " + op);
        };
    }

    /**
     * Map the trigger-written {@code origin} column to {@link Origin}. {@code null} means the
     * row predates the column being added — treat as UNKNOWN and let
     * {@link ChangeEvent#isSyncOriginated()} fall back to the {@code sync_source} convention.
     */
    private static Origin decodeOrigin(String raw) {
        if (raw == null) return Origin.UNKNOWN;
        return switch (raw.trim()) {
            case "SYNC" -> Origin.SYNC;
            case "APP"  -> Origin.APP;
            default     -> Origin.UNKNOWN;
        };
    }
}
