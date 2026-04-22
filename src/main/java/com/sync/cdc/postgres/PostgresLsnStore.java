package com.sync.cdc.postgres;

import com.sync.cdc.spi.LsnStore;
import com.sync.cdc.spi.TrackingTableIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class PostgresLsnStore implements LsnStore<Long> {

    public static final String DEFAULT_TRACKING_TABLE = "public.sync_tracking";

    private static final Logger log = LoggerFactory.getLogger(PostgresLsnStore.class);

    private final JdbcTemplate jdbc;
    private final String trackingTable;

    public PostgresLsnStore(JdbcTemplate jdbc, String trackingTable) {
        this.jdbc = jdbc;
        this.trackingTable = TrackingTableIdentifier.validate(trackingTable);
    }

    @Override
    public Long getLastPosition(String mappingName) {
        return jdbc.queryForObject(
                "SELECT last_position FROM " + trackingTable + " WHERE sync_name = ?",
                Long.class, mappingName);
    }

    @Override
    @Transactional
    public void updatePosition(String mappingName, Long position, int changes) {
        jdbc.update("UPDATE " + trackingTable + """

                SET last_position = ?,
                    last_sync_time = now(),
                    changes_synced = changes_synced + ?
                WHERE sync_name = ?
                """, position, changes, mappingName);
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
    public void initializeIfAbsent(String mappingName, Long initial) {
        int inserted = jdbc.update("INSERT INTO " + trackingTable + """

                    (sync_name, last_position, last_sync_time, changes_synced, errors_count)
                VALUES (?, ?, now(), 0, 0)
                ON CONFLICT (sync_name) DO NOTHING
                """, mappingName, initial);

        if (inserted > 0) {
            log.info("Initialized position tracking for mapping: {}", mappingName);
        }
    }

    @Override
    public String encode(Long position) {
        return position == null ? null : position.toString();
    }

    @Override
    public Long decode(String encoded) {
        return encoded == null ? null : Long.parseLong(encoded);
    }
}
