package com.sync.core;

import com.sync.model.ChangeEvent;
import com.sync.model.SyncCommand;

import java.util.List;

/**
 * Core abstraction: defines how changes from one set of source capture instances
 * map to write operations on a set of target tables.
 *
 * <p>Implement one SyncMapping per "bounded context" and direction.
 *
 * @param <P> the position type of the platform this mapping runs on
 *            (e.g. {@code Long} for PostgreSQL, a byte[] wrapper for SQL Server)
 */
public interface SyncMapping<P extends Comparable<P>> {

    /**
     * Unique name for this mapping (used for position tracking and logging).
     */
    String name();

    /**
     * The capture instances this mapping listens to.
     * <ul>
     *   <li>SQL Server: names created by {@code sp_cdc_enable_table}, e.g. {@code "dbo_Orders"}.</li>
     *   <li>PostgreSQL: source table names that have audit triggers installed, e.g. {@code "orders"}.</li>
     * </ul>
     */
    List<String> sourceCaptureInstances();

    /**
     * Transform a change event from any of the source capture instances
     * into zero or more write commands for the target tables.
     *
     * <p>Returning empty means "skip this change" (e.g. it was sync-originated).
     */
    List<SyncCommand> map(ChangeEvent<P> event);

    /**
     * Direction identifier for logging and metrics.
     */
    SyncDirection direction();

    /**
     * Name of the {@link com.sync.cdc.spi.SyncSink} bean that this mapping's commands should be
     * dispatched to. Defaults to {@code "default"}, which the built-in adapter configs wire to a
     * JDBC sink against the local database.
     *
     * <p>Override to route commands to a different sink (e.g. a REST API sink that pushes to
     * another module's HTTP endpoint).
     */
    default String sinkName() {
        return "default";
    }

    enum SyncDirection {
        LEGACY_TO_NEW,
        NEW_TO_LEGACY
    }
}
