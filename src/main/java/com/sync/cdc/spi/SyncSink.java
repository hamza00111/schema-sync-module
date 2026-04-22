package com.sync.cdc.spi;

import com.sync.core.SyncMapping;
import com.sync.model.SyncCommand;

import java.util.List;

/**
 * Dispatches a batch of {@link SyncCommand}s to a target system.
 *
 * <p>The built-in JDBC implementation (executing MERGE/DELETE against a relational target) is
 * one of several possible sinks. Consumers may plug in e.g. a REST API sink to push changes into
 * a service that owns its own schema.
 *
 * <p>Routing from a mapping to a sink uses bean names: each sink advertises a name via
 * {@link #name()}, and a {@link com.sync.core.SyncMapping} picks one via
 * {@code SyncMapping.sinkName()} (default {@code "default"}).
 *
 * <p>Semantics expected by {@link com.sync.core.SyncEngine}:
 * <ul>
 *   <li>Throwing from {@link #dispatch(SyncMapping, List)} bubbles up; the engine records a
 *       per-event error and continues with the next event, so a single poison command doesn't
 *       block the batch.</li>
 *   <li>If the sink wants to fail the whole batch and have the bookmark not advance, it must
 *       throw from the outer transaction rather than per-command.</li>
 * </ul>
 */
public interface SyncSink {

    /** Bean-local identifier, referenced by {@code SyncMapping.sinkName()}. */
    String name();

    /**
     * Apply the given commands to the target.
     *
     * <p>The {@code mapping} parameter is passed so platform-aware sinks can stamp the
     * transaction with loop-prevention metadata (see
     * {@link WriteDialect#stampSyncOrigin(org.springframework.jdbc.core.JdbcTemplate, String)}).
     * Sinks that don't need this (e.g. HTTP) may ignore it.
     */
    void dispatch(SyncMapping<?> mapping, List<SyncCommand> commands);
}
