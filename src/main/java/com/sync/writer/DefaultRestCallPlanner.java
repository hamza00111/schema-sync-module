package com.sync.writer;

import com.sync.model.SyncCommand;
import org.springframework.http.HttpMethod;

import java.util.function.Function;

/**
 * A reasonable default mapping from {@link SyncCommand} to {@link RestCall} for REST APIs that
 * expose one endpoint per logical entity:
 *
 * <ul>
 *   <li>{@code UPSERT} → {@code POST /{targetTable}} with {@link SyncCommand#values()} as the JSON body</li>
 *   <li>{@code DELETE} → {@code DELETE /{targetTable}/{keyValue}} (single-column key only)</li>
 * </ul>
 *
 * <p>The command's {@code targetTable} is reinterpreted as a logical entity path segment
 * (e.g. {@code "catalog/premium-subscriptions"}).
 *
 * <p>If your API doesn't fit this shape (different verbs, nested paths, composite keys in the
 * body, etc.) pass your own {@code Function<SyncCommand, RestCall>} to
 * {@link RestApiSyncSink}'s constructor instead.
 */
public final class DefaultRestCallPlanner implements Function<SyncCommand, RestCall> {

    @Override
    public RestCall apply(SyncCommand cmd) {
        return switch (cmd.type()) {
            case UPSERT -> new RestCall(HttpMethod.POST, "/" + cmd.targetTable(), cmd.values());
            case DELETE -> {
                String[] keys = cmd.keyColumns();
                if (keys.length != 1) {
                    throw new IllegalArgumentException(
                            "DefaultRestCallPlanner only supports single-column keys for DELETE; "
                                    + "got " + keys.length + " keys for " + cmd.targetTable()
                                    + ". Provide a custom planner for composite keys.");
                }
                Object keyValue = cmd.values().get(keys[0]);
                yield new RestCall(HttpMethod.DELETE, "/" + cmd.targetTable() + "/" + keyValue, null);
            }
        };
    }
}
