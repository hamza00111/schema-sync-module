package com.sync.observability;

import com.sync.core.SyncEngine.SyncResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Micrometer metrics for sync operations.
 * Exposes:
 *   - schema_sync.changes.total{mapping, direction}    counter
 *   - schema_sync.commands.total{mapping, direction}   counter
 *   - schema_sync.errors.total{mapping}                counter
 *   - schema_sync.cycle.duration{mapping}              timer
 *
 * Only active when Micrometer is on the classpath (optional dependency).
 */
@Component
@ConditionalOnClass(MeterRegistry.class)
public class SyncMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> changeCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> commandCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> errorCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public SyncMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCycle(SyncResult<?> result, long durationMillis, String direction) {
        String mapping = result.mappingName();

        changeCounters.computeIfAbsent(mapping, k -> Counter.builder("schema_sync.changes.total")
                        .tag("mapping", mapping)
                        .tag("direction", direction)
                        .description("Total CDC changes observed")
                        .register(registry))
                .increment(result.totalChanges());

        commandCounters.computeIfAbsent(mapping, k -> Counter.builder("schema_sync.commands.total")
                        .tag("mapping", mapping)
                        .tag("direction", direction)
                        .description("Total write commands executed")
                        .register(registry))
                .increment(result.commandsExecuted());

        if (result.hasErrors()) {
            errorCounters.computeIfAbsent(mapping, k -> Counter.builder("schema_sync.errors.total")
                            .tag("mapping", mapping)
                            .description("Total sync errors")
                            .register(registry))
                    .increment(result.errors().size());
        }

        timers.computeIfAbsent(mapping, k -> Timer.builder("schema_sync.cycle.duration")
                        .tag("mapping", mapping)
                        .description("Sync cycle duration")
                        .register(registry))
                .record(java.time.Duration.ofMillis(durationMillis));
    }
}
