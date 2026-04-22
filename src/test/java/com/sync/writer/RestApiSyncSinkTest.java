package com.sync.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sync.model.SyncCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestApiSyncSinkTest {

    private MockRestServiceServer server;
    private RestApiSyncSink sink;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
        server = MockRestServiceServer.bindTo(builder).build();
        sink = new RestApiSyncSink("rest", builder.build(), new DefaultRestCallPlanner());
        mapper = new ObjectMapper();
    }

    @Test
    void upsert_issuesPostWithJsonBodyAtEntityPath() throws Exception {
        Map<String, Object> payload = Map.of("id", 42, "name", "Premium");
        String expectedJson = mapper.writeValueAsString(payload);

        server.expect(requestTo("http://catalog.test/catalog/premium-subscriptions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(expectedJson))
                .andRespond(withSuccess());

        sink.dispatch(List.of(SyncCommand.upsert("catalog/premium-subscriptions", payload, "id")));

        server.verify();
    }

    @Test
    void delete_issuesDeleteWithKeyInPath() {
        server.expect(requestTo("http://catalog.test/catalog/premium-subscriptions/42"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        sink.dispatch(List.of(SyncCommand.delete(
                "catalog/premium-subscriptions",
                Map.of("id", 42),
                "id")));

        server.verify();
    }

    @Test
    void clientError_bubblesUp_soEngineRecordsPerEventError() {
        server.expect(requestTo("http://catalog.test/catalog/premium-subscriptions"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> sink.dispatch(List.of(
                        SyncCommand.upsert("catalog/premium-subscriptions", Map.of("id", 1), "id"))))
                .isInstanceOf(HttpClientErrorException.class);
    }

    @Test
    void serverError_bubblesUp() {
        server.expect(requestTo("http://catalog.test/catalog/premium-subscriptions"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> sink.dispatch(List.of(
                        SyncCommand.upsert("catalog/premium-subscriptions", Map.of("id", 1), "id"))))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void multipleCommands_areDispatchedInOrder() {
        server.expect(requestTo("http://catalog.test/catalog/premium-subscriptions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());
        server.expect(requestTo("http://catalog.test/catalog/premium-subscriptions/7"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        sink.dispatch(List.of(
                SyncCommand.upsert("catalog/premium-subscriptions", Map.of("id", 7, "name", "X"), "id"),
                SyncCommand.delete("catalog/premium-subscriptions", Map.of("id", 7), "id")));

        server.verify();
    }

    @Test
    void defaultPlanner_rejectsCompositeKeyDeletes() {
        DefaultRestCallPlanner planner = new DefaultRestCallPlanner();
        SyncCommand composite = SyncCommand.delete(
                "orders",
                Map.of("a", 1, "b", 2),
                "a", "b");

        assertThatThrownBy(() -> planner.apply(composite))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single-column keys");
    }

    @Test
    void name_isReturned() {
        assertThat(sink.name()).isEqualTo("rest");
    }
}
