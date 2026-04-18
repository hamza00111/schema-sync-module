package com.sync.observability;

import com.sync.cdc.spi.HealthQueries;
import com.sync.config.SyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Exposes sync health at {@code /actuator/health/schemaSync}.
 *
 * <p>Reports DOWN / DEGRADED when any mapping is stale or has recent errors.
 */
@Component
@ConditionalOnClass(HealthIndicator.class)
public class SyncHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(SyncHealthIndicator.class);
    private static final long STALENESS_THRESHOLD_MINUTES = 5;

    private final JdbcTemplate jdbc;
    private final SyncProperties properties;
    private final HealthQueries healthQueries;

    public SyncHealthIndicator(JdbcTemplate jdbc, SyncProperties properties, HealthQueries healthQueries) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.healthQueries = healthQueries;
    }

    @Override
    public Health health() {
        if (!properties.isEnabled()) {
            return Health.up().withDetail("status", "disabled").build();
        }

        try {
            List<Map<String, Object>> rows = jdbc.queryForList(healthQueries.stalenessQuery());

            Health.Builder builder = Health.up();
            boolean anyStale = false;
            boolean anyErrors = false;

            for (Map<String, Object> row : rows) {
                String name = (String) row.get("sync_name");
                Number staleness = (Number) row.get("minutes_since_sync");
                Number errors = (Number) row.get("errors_count");

                builder.withDetail(name, Map.of(
                        "lastSyncTime", row.get("last_sync_time"),
                        "changesSynced", row.get("changes_synced"),
                        "errorsCount", errors,
                        "minutesSinceSync", staleness
                ));

                if (staleness != null && staleness.longValue() > STALENESS_THRESHOLD_MINUTES) {
                    anyStale = true;
                }
                if (errors != null && errors.longValue() > 0) {
                    anyErrors = true;
                }
            }

            if (anyStale) {
                log.warn("Sync health DEGRADED: one or more mappings have stale sync");
                return builder.status("DEGRADED")
                        .withDetail("reason", "One or more mappings have stale sync")
                        .build();
            }
            if (anyErrors) {
                log.warn("Sync health DEGRADED: sync errors detected");
                return builder.status("DEGRADED")
                        .withDetail("reason", "Sync errors detected")
                        .build();
            }

            return builder.build();

        } catch (Exception e) {
            log.error("Sync health check failed: {}", e.getMessage(), e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
