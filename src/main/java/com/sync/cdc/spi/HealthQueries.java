package com.sync.cdc.spi;

/**
 * Platform-specific read-only SQL for health / observability.
 *
 * <p>Keeps date-arithmetic and tracking-table references out of the core health indicator.
 */
public interface HealthQueries {

    /**
     * Returns a query producing one row per sync mapping with these columns:
     * <ul>
     *   <li>{@code sync_name} (string)</li>
     *   <li>{@code last_sync_time} (timestamp)</li>
     *   <li>{@code changes_synced} (number)</li>
     *   <li>{@code errors_count} (number)</li>
     *   <li>{@code minutes_since_sync} (number)</li>
     * </ul>
     */
    String stalenessQuery();
}
