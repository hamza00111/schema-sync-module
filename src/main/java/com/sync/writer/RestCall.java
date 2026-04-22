package com.sync.writer;

import org.springframework.http.HttpMethod;

/**
 * A planned HTTP call produced by a {@link java.util.function.Function Function&lt;SyncCommand, RestCall&gt;}
 * and executed by {@link RestApiSyncSink}.
 *
 * @param method HTTP verb (GET/POST/PUT/PATCH/DELETE)
 * @param path   URI path (absolute path or URI); resolved against the {@code RestClient}'s base URL
 * @param body   request body; may be {@code null} (e.g. DELETE)
 */
public record RestCall(HttpMethod method, String path, Object body) {
}
