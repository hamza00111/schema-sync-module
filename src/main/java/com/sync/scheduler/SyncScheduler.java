package com.sync.scheduler;

import com.sync.config.SyncProperties;
import com.sync.core.SyncEngine;
import com.sync.core.SyncMapping;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Auto-discovers all {@link SyncMapping} beans in the application context
 * and runs them on a schedule.
 *
 * <p>Teams only need to define their SyncMapping implementations as {@code @Component} and
 * configure the polling interval in {@code application.yml}. The scheduler handles the rest.
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final SyncEngine<?> syncEngine;
    private final List<SyncMapping<?>> mappings;
    private final SyncProperties properties;

    public SyncScheduler(SyncEngine<?> syncEngine, List<SyncMapping<?>> mappings, SyncProperties properties) {
        this.syncEngine = syncEngine;
        this.mappings = mappings;
        this.properties = properties;
    }

    @PostConstruct
    void initialize() {
        log.info("Schema Sync Module: discovered {} mapping(s)", mappings.size());
        for (SyncMapping<?> mapping : mappings) {
            log.info("  - {} [{}] listening to {}",
                    mapping.name(), mapping.direction(), mapping.sourceCaptureInstances());
            initAndRun(mapping, true);
        }
    }

    /**
     * Main sync loop. Runs all mappings sequentially.
     */
    @Scheduled(fixedDelayString = "${schema-sync.poll-interval-ms:5000}")
    public void syncAll() {
        if (!properties.isEnabled()) {
            log.debug("Sync is disabled — skipping scheduled cycle");
            return;
        }

        for (SyncMapping<?> mapping : mappings) {
            try {
                initAndRun(mapping, false);
            } catch (Exception e) {
                log.error("Sync failed for mapping [{}]: {}", mapping.name(), e.getMessage());
            }
        }
    }

    /** Erase the wildcard to {@code P} once so the engine stays type-safe internally. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void initAndRun(SyncMapping<?> mapping, boolean initializeOnly) {
        SyncEngine engine = this.syncEngine;
        SyncMapping raw = mapping;
        if (initializeOnly) {
            engine.initialize(raw);
        } else {
            engine.sync(raw);
        }
    }
}
