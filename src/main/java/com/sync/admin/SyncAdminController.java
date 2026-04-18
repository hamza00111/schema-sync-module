package com.sync.admin;

import com.sync.cdc.spi.CdcSource;
import com.sync.cdc.spi.HealthQueries;
import com.sync.cdc.spi.LsnStore;
import com.sync.config.SyncProperties;
import com.sync.core.SyncEngine;
import com.sync.core.SyncMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for operating the sync service during a migration.
 *
 * <p>Enable with {@code schema-sync.admin.enabled: true}.
 *
 * <p>SECURITY: protect these endpoints with Spring Security in production.
 *
 * @param <P> the platform position type
 */
@RestController
@RequestMapping("/admin/sync")
@ConditionalOnProperty(name = "schema-sync.admin.enabled", havingValue = "true")
public class SyncAdminController<P extends Comparable<P>> {

    private static final Logger log = LoggerFactory.getLogger(SyncAdminController.class);

    private final SyncEngine<P> syncEngine;
    private final CdcSource<P> cdcSource;
    private final LsnStore<P> lsnStore;
    private final HealthQueries healthQueries;
    private final List<SyncMapping<P>> mappings;
    private final SyncProperties properties;
    private final JdbcTemplate jdbc;

    public SyncAdminController(SyncEngine<P> syncEngine,
                               CdcSource<P> cdcSource,
                               LsnStore<P> lsnStore,
                               HealthQueries healthQueries,
                               List<SyncMapping<P>> mappings,
                               SyncProperties properties,
                               JdbcTemplate jdbc) {
        this.syncEngine = syncEngine;
        this.cdcSource = cdcSource;
        this.lsnStore = lsnStore;
        this.healthQueries = healthQueries;
        this.mappings = mappings;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /** List all registered mappings and their current state. */
    @GetMapping("/mappings")
    public List<Map<String, Object>> listMappings() {
        return jdbc.queryForList(healthQueries.stalenessQuery());
    }

    /** Trigger a sync cycle immediately for a specific mapping. */
    @PostMapping("/mappings/{name}/run")
    public SyncEngine.SyncResult<P> runNow(@PathVariable String name) {
        log.info("Manual sync triggered for mapping [{}]", name);
        SyncEngine.SyncResult<P> result = syncEngine.sync(findMapping(name));
        log.info("Manual sync completed for [{}]: {} changes, {} commands, {} errors",
                name, result.totalChanges(), result.commandsExecuted(), result.errors().size());
        return result;
    }

    /** Pause the global sync scheduler. */
    @PostMapping("/pause")
    public Map<String, Object> pause() {
        log.warn("Sync PAUSED globally via admin endpoint");
        properties.setEnabled(false);
        return Map.of("enabled", false, "message", "Sync paused globally");
    }

    @PostMapping("/resume")
    public Map<String, Object> resume() {
        log.info("Sync RESUMED globally via admin endpoint");
        properties.setEnabled(true);
        return Map.of("enabled", true, "message", "Sync resumed");
    }

    /**
     * Reset a mapping's position bookmark.
     *
     * <p>DANGEROUS: use only when you know what you're doing (e.g. after a retention breach
     * and a manual full reconciliation).
     *
     * @param mode "min" = replay from oldest available position, "max" = skip to current (ignore backlog)
     */
    @PostMapping("/mappings/{name}/reset")
    public Map<String, Object> reset(@PathVariable String name,
                                     @RequestParam(defaultValue = "max") String mode) {
        SyncMapping<P> mapping = findMapping(name);

        P targetPos;
        if ("min".equalsIgnoreCase(mode)) {
            targetPos = mapping.sourceCaptureInstances().stream()
                    .map(cdcSource::getMinPosition)
                    .min(Comparator.naturalOrder())
                    .orElseThrow();
        } else {
            targetPos = cdcSource.getMaxPosition();
        }

        lsnStore.updatePosition(mapping.name(), targetPos, 0);
        log.warn("Position RESET for mapping [{}] to mode={}, newPosition={}",
                name, mode, lsnStore.encode(targetPos));
        return Map.of(
                "mapping", name,
                "mode", mode,
                "newPosition", lsnStore.encode(targetPos),
                "warning", "Bookmark reset — verify data consistency before resuming"
        );
    }

    /** Inspect a mapping's configuration (which capture instances it reads). */
    @GetMapping("/mappings/{name}")
    public Map<String, Object> getMapping(@PathVariable String name) {
        SyncMapping<P> mapping = findMapping(name);
        return Map.of(
                "name", mapping.name(),
                "direction", mapping.direction().toString(),
                "captureInstances", mapping.sourceCaptureInstances(),
                "lastPosition", lsnStore.encode(lsnStore.getLastPosition(mapping.name()))
        );
    }

    private SyncMapping<P> findMapping(String name) {
        return mappings.stream()
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown mapping: " + name));
    }
}
