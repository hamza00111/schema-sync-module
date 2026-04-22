package com.sync.cdc.sqlserver;

import com.sync.cdc.spi.HealthQueries;
import com.sync.cdc.spi.TrackingTableIdentifier;

public class SqlServerHealthQueries implements HealthQueries {

    private final String trackingTable;

    public SqlServerHealthQueries(String trackingTable) {
        this.trackingTable = TrackingTableIdentifier.validate(trackingTable);
    }

    @Override
    public String stalenessQuery() {
        return "SELECT "
                + "sync_name, "
                + "last_sync_time, "
                + "changes_synced, "
                + "errors_count, "
                + "DATEDIFF(MINUTE, last_sync_time, GETDATE()) AS minutes_since_sync "
                + "FROM " + trackingTable;
    }
}
