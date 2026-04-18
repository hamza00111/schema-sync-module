package com.sync.cdc.postgres;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresWriteDialectTest {

    private final PostgresWriteDialect dialect = new PostgresWriteDialect();

    @Test
    void quoteIdentifier_wrapsInDoubleQuotesAndEscapes() {
        assertThat(dialect.quoteIdentifier("foo")).isEqualTo("\"foo\"");
        assertThat(dialect.quoteIdentifier("col\"with\"quote")).isEqualTo("\"col\"\"with\"\"quote\"");
    }

    @Test
    void buildUpsert_producesInsertOnConflictDoUpdate() {
        String sql = dialect.buildUpsert(
                "public.orders",
                List.of("id", "customer", "total", "sync_source"),
                List.of("id"));

        assertThat(sql).isEqualTo(
                "INSERT INTO public.orders (\"id\", \"customer\", \"total\", \"sync_source\") " +
                        "VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT (\"id\") " +
                        "DO UPDATE SET \"customer\" = EXCLUDED.\"customer\", \"total\" = EXCLUDED.\"total\", \"sync_source\" = EXCLUDED.\"sync_source\"");
    }

    @Test
    void buildUpsert_whenOnlyKeyColumns_usesDoNothing() {
        String sql = dialect.buildUpsert(
                "public.mapping",
                List.of("a", "b"),
                List.of("a", "b"));

        assertThat(sql).endsWith("ON CONFLICT (\"a\", \"b\") DO NOTHING");
    }

    @Test
    void buildUpsert_compositeKey() {
        String sql = dialect.buildUpsert(
                "public.order_lines",
                List.of("order_id", "line_id", "qty"),
                List.of("order_id", "line_id"));

        assertThat(sql).contains("ON CONFLICT (\"order_id\", \"line_id\")");
        assertThat(sql).contains("DO UPDATE SET \"qty\" = EXCLUDED.\"qty\"");
    }

    @Test
    void buildDelete_singleKey() {
        String sql = dialect.buildDelete("public.orders", List.of("id"));
        assertThat(sql).isEqualTo("DELETE FROM public.orders WHERE \"id\" = ?");
    }

    @Test
    void buildDelete_compositeKey() {
        String sql = dialect.buildDelete("public.order_lines", List.of("order_id", "line_id"));
        assertThat(sql).isEqualTo("DELETE FROM public.order_lines WHERE \"order_id\" = ? AND \"line_id\" = ?");
    }

    @Test
    void validateTableName_rejectsInjection() {
        assertThatThrownBy(() -> dialect.validateTableName("orders; DROP TABLE users;--"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateTableName_acceptsSchemaQualifiedAndQuoted() {
        dialect.validateTableName("public.orders");
        dialect.validateTableName("\"public\".\"orders\"");
    }
}
