package com.sync.cdc.spi;

/**
 * Platform-specific persistence for per-mapping sync bookmarks and counters.
 *
 * <p>Backing table defaults are platform-specific (SQL Server: {@code dbo.SyncTracking},
 * PostgreSQL: {@code public.sync_tracking}) and can be overridden via the
 * {@code schema-sync.tracking-table} property. Semantics are identical across platforms.
 *
 * @param <P> the position type for this platform
 */
public interface LsnStore<P extends Comparable<P>> {

    /** Last processed position for a mapping. */
    P getLastPosition(String mappingName);

    /** Advance the bookmark and increment the changes counter. */
    void updatePosition(String mappingName, P position, int changes);

    /** Increment the error counter. Called when a sync cycle fails. */
    void incrementErrors(String mappingName);

    /**
     * Ensure a tracking row exists for this mapping, using {@code initial} as the starting position.
     * No-op if the row already exists.
     */
    void initializeIfAbsent(String mappingName, P initial);

    /** Encode a position to an opaque string (for JSON responses and logs). */
    String encode(P position);

    /** Decode an opaque string back to a position. */
    P decode(String encoded);
}
