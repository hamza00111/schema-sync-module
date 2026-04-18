package com.sync.cdc.sqlserver;

import com.sync.cdc.spi.LsnStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class SqlServerLsnStore implements LsnStore<ByteArrayPosition> {

    private static final Logger log = LoggerFactory.getLogger(SqlServerLsnStore.class);

    private final JdbcTemplate jdbc;

    public SqlServerLsnStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ByteArrayPosition getLastPosition(String mappingName) {
        byte[] lsn = jdbc.queryForObject(
                "SELECT last_lsn FROM dbo.SyncTracking WHERE sync_name = ?",
                byte[].class, mappingName);
        return new ByteArrayPosition(lsn);
    }

    @Override
    @Transactional
    public void updatePosition(String mappingName, ByteArrayPosition position, int changes) {
        jdbc.update("""
                UPDATE dbo.SyncTracking
                SET last_lsn = ?,
                    last_sync_time = GETDATE(),
                    changes_synced = changes_synced + ?
                WHERE sync_name = ?
                """, position.bytes(), changes, mappingName);
    }

    @Override
    @Transactional
    public void incrementErrors(String mappingName) {
        jdbc.update("""
                UPDATE dbo.SyncTracking
                SET errors_count = errors_count + 1
                WHERE sync_name = ?
                """, mappingName);
    }

    @Override
    @Transactional
    public void initializeIfAbsent(String mappingName, ByteArrayPosition initial) {
        int updated = jdbc.update("""
                IF NOT EXISTS (SELECT 1 FROM dbo.SyncTracking WHERE sync_name = ?)
                INSERT INTO dbo.SyncTracking (sync_name, last_lsn, last_sync_time, changes_synced, errors_count)
                VALUES (?, ?, GETDATE(), 0, 0)
                """, mappingName, mappingName, initial.bytes());

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
