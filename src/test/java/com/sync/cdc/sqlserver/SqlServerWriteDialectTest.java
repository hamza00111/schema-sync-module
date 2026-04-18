package com.sync.cdc.sqlserver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlServerWriteDialectTest {

    private final SqlServerWriteDialect dialect = new SqlServerWriteDialect();

    @Test
    void quoteIdentifier_wrapsInBracketsAndEscapes() {
        assertThat(dialect.quoteIdentifier("foo")).isEqualTo("[foo]");
        assertThat(dialect.quoteIdentifier("with]bracket")).isEqualTo("[with]]bracket]");
    }

    @Test
    void buildUpsert_producesMergeWithMatchedAndNotMatchedBranches() {
        String sql = dialect.buildUpsert(
                "dbo.Orders",
                List.of("id", "customer", "total", "sync_source"),
                List.of("id"));

        assertThat(sql).startsWith("MERGE dbo.Orders AS target");
        assertThat(sql).contains("USING (SELECT ? AS [id], ? AS [customer], ? AS [total], ? AS [sync_source]) AS source");
        assertThat(sql).contains("ON (target.[id] = source.[id])");
        assertThat(sql).contains("WHEN MATCHED THEN UPDATE SET target.[customer] = source.[customer], target.[total] = source.[total], target.[sync_source] = source.[sync_source]");
        assertThat(sql).contains("WHEN NOT MATCHED THEN INSERT ([id], [customer], [total], [sync_source]) VALUES (source.[id], source.[customer], source.[total], source.[sync_source])");
        assertThat(sql).endsWith(";");
    }

    @Test
    void buildUpsert_whenOnlyKeyColumns_omitsUpdateBranch() {
        String sql = dialect.buildUpsert(
                "dbo.Mapping",
                List.of("a", "b"),
                List.of("a", "b"));

        assertThat(sql).doesNotContain("WHEN MATCHED");
        assertThat(sql).contains("WHEN NOT MATCHED THEN INSERT ([a], [b])");
    }

    @Test
    void buildUpsert_compositeKey_buildsConjunctiveOn() {
        String sql = dialect.buildUpsert(
                "dbo.Orders",
                List.of("order_id", "line_id", "qty"),
                List.of("order_id", "line_id"));

        assertThat(sql).contains("ON (target.[order_id] = source.[order_id] AND target.[line_id] = source.[line_id])");
        assertThat(sql).contains("UPDATE SET target.[qty] = source.[qty]");
    }

    @Test
    void buildDelete_singleKey() {
        String sql = dialect.buildDelete("dbo.Orders", List.of("id"));
        assertThat(sql).isEqualTo("DELETE FROM dbo.Orders WHERE [id] = ?");
    }

    @Test
    void buildDelete_compositeKey() {
        String sql = dialect.buildDelete("dbo.OrderLines", List.of("order_id", "line_id"));
        assertThat(sql).isEqualTo("DELETE FROM dbo.OrderLines WHERE [order_id] = ? AND [line_id] = ?");
    }

    @Test
    void validateTableName_rejectsInjection() {
        assertThatThrownBy(() -> dialect.validateTableName("dbo.Orders; DROP TABLE users;--"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dialect.validateTableName("dbo.'Orders"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateTableName_acceptsSchemaQualifiedAndBracketed() {
        dialect.validateTableName("dbo.Orders");
        dialect.validateTableName("[dbo].[Orders]");
    }
}
