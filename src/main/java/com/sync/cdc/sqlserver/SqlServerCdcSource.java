package com.sync.cdc.sqlserver;

import com.sync.cdc.spi.CdcSource;
import com.sync.model.ChangeEvent;
import com.sync.model.ChangeEvent.OperationType;
import com.sync.model.ChangeEvent.Origin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads change data from SQL Server CDC system functions.
 */
public class SqlServerCdcSource implements CdcSource<ByteArrayPosition> {

    private static final Logger log = LoggerFactory.getLogger(SqlServerCdcSource.class);

    /** Capture instance name for the sidecar marker table (see {@link SqlServerWriteDialect#MARKER_TABLE}). */
    private static final String MARKER_CAPTURE_INSTANCE = "dbo_sync_markers";

    private final JdbcTemplate jdbc;
    private final AtomicBoolean markerMissingWarned = new AtomicBoolean();

    public SqlServerCdcSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ByteArrayPosition getMaxPosition() {
        byte[] lsn = jdbc.queryForObject("SELECT sys.fn_cdc_get_max_lsn()", byte[].class);
        return new ByteArrayPosition(lsn);
    }

    @Override
    public ByteArrayPosition getMinPosition(String captureInstance) {
        byte[] lsn = jdbc.queryForObject(
                "SELECT sys.fn_cdc_get_min_lsn(?)", byte[].class, captureInstance);
        return new ByteArrayPosition(lsn);
    }

    @Override
    public List<ChangeEvent<ByteArrayPosition>> getNetChanges(
            String captureInstance, ByteArrayPosition from, ByteArrayPosition to) {

        if (from.compareTo(to) >= 0) {
            return Collections.emptyList();
        }

        ByteArrayPosition minLsn = getMinPosition(captureInstance);
        ByteArrayPosition effectiveFrom = from;
        if (from.compareTo(minLsn) < 0) {
            log.warn("Capture instance [{}]: last processed LSN is before CDC min LSN. " +
                    "CDC retention may have been exceeded. Resetting to min LSN.", captureInstance);
            effectiveFrom = minLsn;
        }

        Set<ByteArrayPosition> syncLsns = loadSyncOriginLsns(effectiveFrom, to);

        String sql = String.format(
                "SELECT * FROM cdc.fn_cdc_get_net_changes_%s(?, ?, 'all with merge')",
                sanitizeCaptureInstance(captureInstance));

        List<Map<String, Object>> rows = jdbc.queryForList(sql, effectiveFrom.bytes(), to.bytes());

        return rows.stream()
                .map(row -> toChangeEvent(captureInstance, row, syncLsns))
                .toList();
    }

    /**
     * Load every {@code __$start_lsn} in the (from, to] range that corresponds to a sync-origin
     * transaction, by reading the CDC change table of the marker sidecar. Returns {@code null}
     * when the marker capture table is missing — in that case events are stamped
     * {@link Origin#UNKNOWN} and callers fall back to the legacy {@code sync_source} column
     * convention via {@link ChangeEvent#isSyncOriginated()}.
     */
    private Set<ByteArrayPosition> loadSyncOriginLsns(ByteArrayPosition from, ByteArrayPosition to) {
        try {
            List<byte[]> lsns = jdbc.queryForList(
                    "SELECT DISTINCT __$start_lsn FROM cdc." + MARKER_CAPTURE_INSTANCE + "_CT " +
                            "WHERE __$start_lsn > ? AND __$start_lsn <= ?",
                    byte[].class, from.bytes(), to.bytes());
            Set<ByteArrayPosition> result = new HashSet<>(lsns.size());
            for (byte[] lsn : lsns) {
                result.add(new ByteArrayPosition(lsn));
            }
            return result;
        } catch (DataAccessException e) {
            if (markerMissingWarned.compareAndSet(false, true)) {
                log.warn("Marker capture table cdc.{}_CT is missing — loop-prevention will rely on " +
                        "the `sync_source` column only. Apply sql/setup-sqlserver.sql to enable " +
                        "the column-less path. Cause: {}", MARKER_CAPTURE_INSTANCE, e.getMessage());
            }
            return null;
        }
    }

    private ChangeEvent<ByteArrayPosition> toChangeEvent(
            String captureInstance, Map<String, Object> row, Set<ByteArrayPosition> syncLsns) {
        int operationCode = ((Number) row.get("__$operation")).intValue();
        byte[] lsn = (byte[]) row.get("__$start_lsn");
        ByteArrayPosition position = new ByteArrayPosition(lsn);

        Map<String, Object> columns = new LinkedHashMap<>(row);
        columns.remove("__$operation");
        columns.remove("__$start_lsn");
        columns.remove("__$seqval");
        columns.remove("__$update_mask");

        Origin origin;
        if (syncLsns == null) {
            origin = Origin.UNKNOWN;
        } else if (syncLsns.contains(position)) {
            origin = Origin.SYNC;
        } else {
            origin = Origin.APP;
        }

        return new ChangeEvent<>(
                captureInstance,
                OperationType.fromCdcCode(operationCode),
                columns,
                position,
                origin
        );
    }

    private String sanitizeCaptureInstance(String captureInstance) {
        if (!captureInstance.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "Invalid capture instance name: " + captureInstance);
        }
        return captureInstance;
    }
}
