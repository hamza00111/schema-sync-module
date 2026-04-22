package com.sync.writer;

import com.sync.cdc.spi.SyncSink;
import com.sync.core.SyncMapping;
import com.sync.model.SyncCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.function.Function;

/**
 * {@link SyncSink} that dispatches each {@link SyncCommand} as an HTTP call to a remote service.
 *
 * <p>Use this when the target of a mapping is the <em>new module's REST API</em> rather than its
 * database. The shape of each call (verb, path, body) is produced by the injected
 * {@code planner} — {@link DefaultRestCallPlanner} covers the common case; users with different
 * API conventions pass their own.
 *
 * <p>Error semantics:
 * <ul>
 *   <li>2xx → success.</li>
 *   <li>Any non-2xx or IO failure throws; {@link com.sync.core.SyncEngine} records the error
 *       against the originating event and continues with the next event. The bookmark still
 *       advances for the events that succeeded, matching the JDBC sink's per-command semantics.</li>
 * </ul>
 *
 * <p>Note: because each command is an independent HTTP call, there is no batch-level rollback.
 * If you need all-or-nothing semantics across a batch, use a JDBC sink or wrap your API to
 * accept bulk payloads and route a single command to it from your mapping.
 */
public class RestApiSyncSink implements SyncSink {

    private static final Logger log = LoggerFactory.getLogger(RestApiSyncSink.class);

    private final String name;
    private final RestClient client;
    private final Function<SyncCommand, RestCall> planner;

    public RestApiSyncSink(String name, RestClient client, Function<SyncCommand, RestCall> planner) {
        this.name = name;
        this.client = client;
        this.planner = planner;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void dispatch(SyncMapping<?> mapping, List<SyncCommand> commands) {
        // Mapping is unused: HTTP targets own their own persistence and cannot loop through our CDC.
        for (SyncCommand cmd : commands) {
            RestCall call = planner.apply(cmd);
            RestClient.RequestBodySpec spec = client.method(call.method()).uri(call.path());
            if (call.body() != null) {
                spec.body(call.body());
            }
            spec.retrieve().toBodilessEntity();
            log.debug("{} {} ({})", call.method(), call.path(), cmd.type());
        }
    }
}
