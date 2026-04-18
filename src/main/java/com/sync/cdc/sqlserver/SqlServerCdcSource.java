package com.sync.cdc.sqlserver;

import com.sync.cdc.spi.CdcSource;
import com.sync.model.ChangeEvent;
import com.sync.model.ChangeEvent.OperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads change data from SQL Server CDC system functions.
 */
public class SqlServerCdcSource implements CdcSource<ByteArrayPosition> {

    private static final Logger log = LoggerFactory.getLogger(SqlServerCdcSource.class);

    private final JdbcTemplate jdbc;

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

        String sql = String.format(
                "SELECT * FROM cdc.fn_cdc_get_net_changes_%s(?, ?, 'all with merge')",
                sanitizeCaptureInstance(captureInstance));

        List<Map<String, Object>> rows = jdbc.queryForList(sql, effectiveFrom.bytes(), to.bytes());

        return rows.stream()
                .map(row -> toChangeEvent(captureInstance, row))
                .toList();
    }

    private ChangeEvent<ByteArrayPosition> toChangeEvent(String captureInstance, Map<String, Object> row) {
        int operationCode = ((Number) row.get("__$operation")).intValue();
        byte[] lsn = (byte[]) row.get("__$start_lsn");

        Map<String, Object> columns = new LinkedHashMap<>(row);
        columns.remove("__$operation");
        columns.remove("__$start_lsn");
        columns.remove("__$seqval");
        columns.remove("__$update_mask");

        return new ChangeEvent<>(
                captureInstance,
                OperationType.fromCdcCode(operationCode),
                columns,
                new ByteArrayPosition(lsn)
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
