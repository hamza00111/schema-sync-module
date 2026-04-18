package com.sync.cdc.postgres;

import com.sync.cdc.spi.HealthQueries;

public class PostgresHealthQueries implements HealthQueries {

    @Override
    public String stalenessQuery() {
        return """
                SELECT
                    sync_name,
                    last_sync_time,
                    changes_synced,
                    errors_count,
                    EXTRACT(EPOCH FROM (now() - last_sync_time)) / 60.0 AS minutes_since_sync
                FROM public.sync_tracking
                """;
    }
}
