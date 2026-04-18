package com.sync.cdc.spi;

import com.sync.model.ChangeEvent;

import java.util.Comparator;
import java.util.List;

/**
 * Platform-specific strategy for reading CDC changes.
 *
 * <p>Implementations:
 * <ul>
 *   <li>SQL Server: reads {@code cdc.fn_cdc_get_net_changes_*} system functions. P = ByteArrayPosition (binary(10) LSN).</li>
 *   <li>PostgreSQL: reads from a trigger-maintained audit table. P = Long (audit row id).</li>
 * </ul>
 *
 * @param <P> the position type for this platform (must be totally ordered)
 */
public interface CdcSource<P extends Comparable<P>> {

    /** Current maximum position available in the source. Used as the upper bound for a sync cycle. */
    P getMaxPosition();

    /** Oldest position still retained for the given capture instance (for replay). */
    P getMinPosition(String captureInstance);

    /**
     * Read collapsed ("net") changes for a single capture instance within (from, to].
     * Returns empty when from >= to.
     */
    List<ChangeEvent<P>> getNetChanges(String captureInstance, P from, P to);

    /** Comparator for positions (delegates to {@link Comparable} by default). */
    default Comparator<P> positionComparator() {
        return Comparator.naturalOrder();
    }
}
