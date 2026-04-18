package com.sync.observability;

import com.sync.cdc.spi.CdcSource;
import com.sync.cdc.spi.LsnStore;
import com.sync.core.SyncMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Monitors CDC lag and detects dangerous conditions.
 *
 * <p>The silent killer in CDC-based sync is when the sync service has been down longer than
 * the source's change retention. When that happens, the source drops change records before
 * they're processed and data is lost without any error being thrown.
 *
 * <p>This monitor alerts when:
 * <ul>
 *   <li>CRITICAL: last processed position is older than the source's min position (retention breach)</li>
 * </ul>
 *
 * <p>Implement {@link CdcAlertListener} to plug into PagerDuty, Slack, email, etc.
 *
 * @param <P> the platform position type
 */
@Component
public class CdcLagMonitor<P extends Comparable<P>> {

    private static final Logger log = LoggerFactory.getLogger(CdcLagMonitor.class);

    private final CdcSource<P> cdcSource;
    private final LsnStore<P> lsnStore;
    private final List<SyncMapping<?>> mappings;
    private final List<CdcAlertListener> alertListeners;

    public CdcLagMonitor(CdcSource<P> cdcSource,
                         LsnStore<P> lsnStore,
                         List<SyncMapping<?>> mappings,
                         List<CdcAlertListener> alertListeners) {
        this.cdcSource = cdcSource;
        this.lsnStore = lsnStore;
        this.mappings = mappings;
        this.alertListeners = alertListeners;
    }

    @Scheduled(fixedDelayString = "${schema-sync.lag-check-interval-ms:60000}")
    public void checkLag() {
        for (SyncMapping<?> mapping : mappings) {
            try {
                checkMapping(mapping);
            } catch (Exception e) {
                log.error("Lag check failed for [{}]: {}", mapping.name(), e.getMessage());
            }
        }
    }

    private void checkMapping(SyncMapping<?> mapping) {
        P lastProcessed = lsnStore.getLastPosition(mapping.name());

        for (String captureInstance : mapping.sourceCaptureInstances()) {
            P minPos = cdcSource.getMinPosition(captureInstance);

            if (lastProcessed.compareTo(minPos) < 0) {
                String msg = String.format(
                        "CDC RETENTION BREACHED for mapping [%s], capture [%s]. " +
                                "Data loss possible. Need full reconciliation.",
                        mapping.name(), captureInstance);
                log.error(msg);
                alert(AlertLevel.CRITICAL, mapping.name(), captureInstance, msg);
            }
        }
    }

    private void alert(AlertLevel level, String mapping, String captureInstance, String message) {
        for (CdcAlertListener listener : alertListeners) {
            try {
                listener.onCdcAlert(level, mapping, captureInstance, message);
            } catch (Exception e) {
                log.error("Alert listener failed: {}", e.getMessage());
            }
        }
    }

    public enum AlertLevel {
        WARNING, CRITICAL
    }

    public interface CdcAlertListener {
        void onCdcAlert(AlertLevel level, String mapping, String captureInstance, String message);
    }
}
