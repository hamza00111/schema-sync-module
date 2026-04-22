package com.sync.cdc.sqlserver;

import com.sync.cdc.spi.LsnStore;
import com.sync.cdc.spi.TrackingTableIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class SqlServerLsnStore implements LsnStore<ByteArrayPosition> {

    public static final String DEFAULT_TRACKING_TABLE = "dbo.SyncTracking";

    private static final Logger log = LoggerFactory.getLogger(SqlServerLsnStore.class);

    private final JdbcTemplate jdbc;
    private final String trackingTable;

    public SqlServerLsnStore(JdbcTemplate jdbc, String trackingTable) {
        this.jdbc = jdbc;
        this.trackingTable = TrackingTableIdentifier.validate(trackingTable);
    }

    @Override
    public ByteArrayPosition getLastPosition(String mappingName) {
        byte[] lsn = jdbc.queryForObject(
                "SELECT last_lsn FROM " + trackingTable + " WHERE sync_name = ?",
                byte[].class, mappingName);
        return new ByteArrayPosition(lsn);
    }

    @Override
    @Transactional
    public void updatePosition(String mappingName, ByteArrayPosition position, int changes) {
        jdbc.update("UPDATE " + trackingTable + """

                SET last_lsn = ?,
                    last_sync_time = GETDATE(),
                    changes_synced = changes_synced + ?
                WHERE sync_name = ?
                """, position.bytes(), changes, mappingName);
    }

    @Override
    @Transactional
    public void incrementErrors(String mappingName) {
        jdbc.update("UPDATE " + trackingTable + """

                SET errors_count = errors_count + 1
                WHERE sync_name = ?
                """, mappingName);
    }

    @Override
    @Transactional
    public void initializeIfAbsent(String mappingName, ByteArrayPosition initial) {
        int updated = jdbc.update(
                "IF NOT EXISTS (SELECT 1 FROM " + trackingTable + " WHERE sync_name = ?) "
                        + "INSERT INTO " + trackingTable
                        + " (sync_name, last_lsn, last_sync_time, changes_synced, errors_count) "
                        + "VALUES (?, ?, GETDATE(), 0, 0)",
                mappingName, mappingName, initial.bytes());

        if (updated > 0) {
            log.info("Initialized LSN tracking for mapping: {}", mappingName);
        }
    }

    @Override
    public String encode(ByteArrayPosition position) {
        return position.toHex();
    }

    @Override
    public ByteArrayPosition decode(String encoded) {
        return ByteArrayPosition.fromHex(encoded);
    }
}
