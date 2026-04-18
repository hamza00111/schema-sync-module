package com.sync.reconciliation;

import com.sync.config.SyncProperties;
import com.sync.reconciliation.ReconciliationCheck.ReconciliationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs all ReconciliationCheck beans on a schedule.
 * Acts as a safety net to catch any drift between legacy and new schemas
 * that the sync process might have missed.
 */
@Component
public class ReconciliationEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationEngine.class);

    private final List<ReconciliationCheck> checks;
    private final SyncProperties properties;
    private final List<ReconciliationListener> listeners;

    public ReconciliationEngine(
            List<ReconciliationCheck> checks,
            SyncProperties properties,
            List<ReconciliationListener> listeners) {
        this.checks = checks;
        this.properties = properties;
        this.listeners = listeners;
    }

    @Scheduled(cron = "${schema-sync.reconciliation.cron:0 0 * * * *}")
    public void runAll() {
        if (!properties.getReconciliation().isEnabled()) {
            return;
        }

        log.info("Running {} reconciliation check(s)", checks.size());
        List<ReconciliationResult> results = new ArrayList<>();

        for (ReconciliationCheck check : checks) {
            try {
                ReconciliationResult result = check.check();
                results.add(result);

                if (result.passed()) {
                    log.info("  [PASS] {}: {}", result.checkName(), result.message());
                } else {
                    log.warn("  [FAIL] {}: {} (expected={}, actual={})",
                            result.checkName(), result.message(),
                            result.expectedValue(), result.actualValue());
                }
            } catch (Exception e) {
                log.error("  [ERROR] {}: {}", check.name(), e.getMessage(), e);
                results.add(ReconciliationResult.fail(
                        check.name(), "Check threw exception: " + e.getMessage(), null, null));
            }
        }

        // Notify all listeners (alerting, metrics, etc.)
        long failures = results.stream().filter(r -> !r.passed()).count();
        for (ReconciliationListener listener : listeners) {
            listener.onReconciliationComplete(results, failures);
        }
    }

    /**
     * Extension point for alerting/metrics on reconciliation results.
     */
    public interface ReconciliationListener {
        void onReconciliationComplete(List<ReconciliationResult> results, long failures);
    }
}
