package com.sync.core;

import com.sync.cdc.spi.CdcSource;
import com.sync.cdc.spi.LsnStore;
import com.sync.cdc.spi.SyncSink;
import com.sync.model.ChangeEvent;
import com.sync.model.SyncCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates one sync cycle for a given {@link SyncMapping}.
 *
 * <p>A cycle:
 * <ol>
 *   <li>Read the last processed position from the {@link LsnStore}</li>
 *   <li>Get the current max position from the {@link CdcSource}</li>
 *   <li>For each source capture instance, read net changes in (from, to]</li>
 *   <li>For each change, invoke the mapping to produce {@link SyncCommand}s</li>
 *   <li>Dispatch commands via the {@link SyncSink} chosen by the caller</li>
 *   <li>Advance the position bookmark</li>
 * </ol>
 *
 * <p>Stateless — all state lives in the database (tracking table). The sink is passed in per
 * call so one engine instance can drive multiple mappings that target different sinks (JDBC,
 * REST API, etc).
 *
 * @param <P> the position type for this platform
 */
public class SyncEngine<P extends Comparable<P>> {

    private static final Logger log = LoggerFactory.getLogger(SyncEngine.class);

    private final CdcSource<P> cdcSource;
    private final LsnStore<P> lsnStore;

    public SyncEngine(CdcSource<P> cdcSource, LsnStore<P> lsnStore) {
        this.cdcSource = cdcSource;
        this.lsnStore = lsnStore;
    }

    /**
     * Execute one sync cycle. Returns the number of changes processed.
     */
    public SyncResult<P> sync(SyncMapping<P> mapping, SyncSink sink) {
        String mappingName = mapping.name();

        try {
            P fromPos = lsnStore.getLastPosition(mappingName);
            P toPos = cdcSource.getMaxPosition();

            log.debug("[{}] Polling range ({}, {}]", mappingName, fromPos, toPos);

            List<ChangeEvent<P>> allChanges = new ArrayList<>();
            for (String captureInstance : mapping.sourceCaptureInstances()) {
                allChanges.addAll(cdcSource.getNetChanges(captureInstance, fromPos, toPos));
            }

            if (allChanges.isEmpty()) {
                log.debug("[{}] No changes in range", mappingName);
                return SyncResult.noChanges(mappingName);
            }

            int commandsExecuted = 0;
            int changesSkipped = 0;
            List<SyncError<P>> errors = new ArrayList<>();

            for (ChangeEvent<P> event : allChanges) {
                try {
                    List<SyncCommand> commands = mapping.map(event);

                    if (commands.isEmpty()) {
                        changesSkipped++;
                        continue;
                    }

                    sink.dispatch(mapping, commands);
                    commandsExecuted += commands.size();

                } catch (Exception e) {
                    log.error("[{}] Failed to process change from {}: {}",
                            mappingName, event.captureInstance(), e.getMessage(), e);
                    errors.add(new SyncError<>(event, e));
                    // Continue — one bad record doesn't block the rest.
                }
            }

            lsnStore.updatePosition(mappingName, toPos, commandsExecuted);

            if (!errors.isEmpty()) {
                lsnStore.incrementErrors(mappingName);
            }

            SyncResult<P> result = new SyncResult<>(
                    mappingName, allChanges.size(), commandsExecuted, changesSkipped, errors);

            log.info("[{}] Sync complete: {} changes, {} commands, {} skipped, {} errors",
                    mappingName, result.totalChanges(), result.commandsExecuted(),
                    result.changesSkipped(), result.errors().size());

            return result;

        } catch (Exception e) {
            log.error("[{}] Sync cycle failed: {}", mappingName, e.getMessage(), e);
            lsnStore.incrementErrors(mappingName);
            throw new SyncException("Sync cycle failed for mapping: " + mappingName, e);
        }
    }

    /**
     * Initialize position tracking for a mapping. Should be called once at startup.
     */
    public void initialize(SyncMapping<P> mapping) {
        for (String captureInstance : mapping.sourceCaptureInstances()) {
            P minPos = cdcSource.getMinPosition(captureInstance);
            lsnStore.initializeIfAbsent(mapping.name(), minPos);
        }
        log.info("Initialized sync mapping: {}", mapping.name());
    }

    public record SyncResult<P extends Comparable<P>>(
            String mappingName,
            int totalChanges,
            int commandsExecuted,
            int changesSkipped,
            List<SyncError<P>> errors
    ) {
        static <P extends Comparable<P>> SyncResult<P> noChanges(String mappingName) {
            return new SyncResult<>(mappingName, 0, 0, 0, List.of());
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    public record SyncError<P extends Comparable<P>>(ChangeEvent<P> event, Exception exception) {}

    public static class SyncException extends RuntimeException {
        public SyncException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
